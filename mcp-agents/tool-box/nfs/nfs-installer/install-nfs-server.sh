#!/usr/bin/env bash
# Turns an Ubuntu 22.04 VM into the lab's NAS: an NFS server whose shares Kubernetes pods (and other
# VMs) mount. Idempotent: re-running rewrites the exports from the variables and keeps the data.
#
#   NFS_CLIENTS="192.168.1.20 192.168.1.21" ./install-nfs-server.sh
#   NFS_CLIENTS=... DATA_DISK=/dev/sdb ./install-nfs-server.sh     put the shares on a dedicated disk
set -euo pipefail

NFS_ROOT=${NFS_ROOT:-/srv/nfs}
NFS_SHARES=${NFS_SHARES:-k8s shared}          # directories under NFS_ROOT, each exported
NFS_CLIENTS=${NFS_CLIENTS:-}                   # IPs or CIDRs allowed to mount (required)
NFS_ROOT_SQUASH=${NFS_ROOT_SQUASH:-0}          # 0: root in a pod stays root on the share (needed by
                                               #    most containers); 1: root becomes nobody
NFS_THREADS=${NFS_THREADS:-16}
DATA_DISK=${DATA_DISK:-}                       # e.g. /dev/sdb: formatted ext4 ONLY if it is blank
DATA_FS_LABEL=${DATA_FS_LABEL:-nas-data}

log() { printf '\n==> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

[[ $(id -u) == 0 ]] && SUDO="" || SUDO=sudo
$SUDO true || die "needs sudo"
. /etc/os-release
[[ $ID == ubuntu ]] || die "Ubuntu only (found $ID)"
[[ -n $NFS_CLIENTS ]] || die "set NFS_CLIENTS to the IPs/CIDRs allowed to mount, e.g. the Kubernetes node IPs"
for c in $NFS_CLIENTS; do
  [[ $c =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}(/[0-9]{1,2})?$ ]] || die "NFS_CLIENTS: '$c' is not an IPv4 address or CIDR"
  [[ $c == 0.0.0.0/0 || $c == */0 ]] && die "NFS_CLIENTS: refusing to export to everyone"
done
for s in $NFS_SHARES; do [[ $s =~ ^[a-z0-9][a-z0-9._-]*$ ]] || die "NFS_SHARES: bad name '$s'"; done

log "Packages"
$SUDO apt-get update -qq
$SUDO DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nfs-kernel-server rsync >/dev/null
echo "nfs-kernel-server $(dpkg-query -W -f='${Version}' nfs-kernel-server)"

# Ubuntu cloud images on Proxmox often run the minimal -kvm kernel, which has no nfsd module. Install the
# standard virtual kernel, make GRUB prefer it over -kvm, and stop: the server starts after a reboot.
if ! modinfo nfsd >/dev/null 2>&1; then
  log "Kernel $(uname -r) has no NFS server module (nfsd)"
  $SUDO DEBIAN_FRONTEND=noninteractive apt-get install -y -qq linux-virtual >/dev/null
  echo 'GRUB_FLAVOUR_ORDER="generic"   # ai-labs nfs-installer: boot the kernel that has nfsd' \
    | $SUDO tee /etc/default/grub.d/90-ai-labs-nfs.cfg >/dev/null
  $SUDO update-grub 2>&1 | grep -E 'Found linux image' | head -4 | sed 's/^/  /'
  new=$(ls /boot/vmlinuz-*-generic 2>/dev/null | sort -V | tail -1 | sed 's#.*/vmlinuz-##')
  [[ -n $new ]] || die "linux-virtual did not install a -generic kernel"
  [[ -f /lib/modules/$new/kernel/fs/nfsd/nfsd.ko || -f /lib/modules/$new/kernel/fs/nfsd/nfsd.ko.zst ]] \
    || die "kernel $new has no nfsd module either"
  echo "Installed kernel $new (has nfsd). Reboot, then re-run this script with the same variables:"
  echo "  sudo reboot"
  exit 3
fi

# ---- data disk -------------------------------------------------------------------------------------
if [[ -n $DATA_DISK ]]; then
  log "Data disk $DATA_DISK"
  [[ -b $DATA_DISK ]] || die "$DATA_DISK is not a block device"
  root_src=$(findmnt -no SOURCE /)
  [[ $root_src == "$DATA_DISK"* ]] && die "$DATA_DISK holds the root filesystem"
  cur=$(findmnt -no SOURCE "$NFS_ROOT" 2>/dev/null || true)
  if [[ -n $cur && $(blkid -o value -s LABEL "$cur" 2>/dev/null) == "$DATA_FS_LABEL" ]]; then
    echo "already mounted on $NFS_ROOT"
  else
    fstype=$($SUDO blkid -o value -s TYPE "$DATA_DISK" 2>/dev/null || true)
    label=$($SUDO blkid -o value -s LABEL "$DATA_DISK" 2>/dev/null || true)
    if [[ -z $fstype ]]; then
      [[ -z $(lsblk -no NAME "$DATA_DISK" | tail -n +2) ]] || die "$DATA_DISK has partitions; refusing to format"
      echo "blank: creating ext4 (label $DATA_FS_LABEL)"
      $SUDO mkfs.ext4 -q -L "$DATA_FS_LABEL" -m 1 "$DATA_DISK"
    elif [[ $label != "$DATA_FS_LABEL" ]]; then
      die "$DATA_DISK already has a $fstype filesystem (label '$label'); not touching it"
    fi
    uuid=$($SUDO blkid -o value -s UUID "$DATA_DISK")
    # Move what is already shared onto the disk, then mount it in place.
    systemctl is-active -q nfs-server && $SUDO systemctl stop nfs-server
    tmp=$(mktemp -d); $SUDO mount "$DATA_DISK" "$tmp"
    if [[ -d $NFS_ROOT ]] && [[ -n $(ls -A "$NFS_ROOT" 2>/dev/null) ]]; then
      echo "copying existing shares onto the disk"
      $SUDO rsync -aHAX "$NFS_ROOT"/ "$tmp"/
    fi
    $SUDO umount "$tmp"; rmdir "$tmp"
    if [[ -d $NFS_ROOT && -n $(ls -A "$NFS_ROOT" 2>/dev/null) ]]; then
      $SUDO mv "$NFS_ROOT" "$NFS_ROOT.pre-disk.$(date +%Y%m%d%H%M%S)"   # kept until you delete it
    fi
    $SUDO install -d -m 0755 "$NFS_ROOT"
    $SUDO sed -i "\#[[:space:]]$NFS_ROOT[[:space:]]#d" /etc/fstab
    echo "UUID=$uuid $NFS_ROOT ext4 defaults,noatime,nofail 0 2" | $SUDO tee -a /etc/fstab >/dev/null
    $SUDO systemctl daemon-reload
    $SUDO mount "$NFS_ROOT"
    echo "mounted $DATA_DISK on $NFS_ROOT"
  fi
  # Never serve the empty mount point on the system disk if the data disk is missing at boot.
  $SUDO install -d /etc/systemd/system/nfs-server.service.d
  printf '[Unit]\nRequiresMountsFor=%s\n' "$NFS_ROOT" | $SUDO tee /etc/systemd/system/nfs-server.service.d/ai-labs-data-disk.conf >/dev/null
  $SUDO systemctl daemon-reload
fi

# ---- shares ----------------------------------------------------------------------------------------
log "Shares under $NFS_ROOT"
$SUDO install -d -m 0755 "$NFS_ROOT"
for s in $NFS_SHARES; do
  $SUDO install -d -m 0777 "$NFS_ROOT/$s"     # world-writable: pods run as many different UIDs
  echo "  $NFS_ROOT/$s"
done

squash=no_root_squash; [[ $NFS_ROOT_SQUASH == 1 ]] && squash=root_squash
opts="rw,sync,no_subtree_check,$squash"
$SUDO install -d /etc/exports.d
{
  echo "# Managed by ai-labs/mcp-agents/tool-box/nfs/nfs-installer/install-nfs-server.sh: re-running rewrites it."
  echo "# Add your own exports to /etc/exports.d/local.exports instead."
  for s in $NFS_SHARES; do
    line="$NFS_ROOT/$s"
    for c in $NFS_CLIENTS; do line+=" $c($opts)"; done
    echo "$line"
  done
} | $SUDO tee /etc/exports.d/lab.exports >/dev/null
grep -q '^# Managed by ai-labs' /etc/exports 2>/dev/null || \
  echo "# Lab exports live in /etc/exports.d/lab.exports (managed by install-nfs-server.sh)" | $SUDO tee -a /etc/exports >/dev/null

log "NFS server"
# NFSv3 and v4; threads sized for a small cluster.
$SUDO install -d /etc/nfs.conf.d
printf '[nfsd]\nthreads=%s\nvers3=y\nvers4=y\nvers4.1=y\nvers4.2=y\n' "$NFS_THREADS" | $SUDO tee /etc/nfs.conf.d/lab.conf >/dev/null
$SUDO systemctl enable -q nfs-server
$SUDO systemctl restart nfs-server
$SUDO exportfs -ra
$SUDO exportfs -v | sed 's/^/  /'

if command -v ufw >/dev/null && $SUDO ufw status | grep -q 'Status: active'; then
  log "ufw: allowing NFS (2049/tcp, rpcbind 111) from NFS_CLIENTS"
  for c in $NFS_CLIENTS; do
    $SUDO ufw allow from "$c" to any port 2049 proto tcp >/dev/null
    $SUDO ufw allow from "$c" to any port 111 >/dev/null
  done
fi

log "Self-test (mount over loopback is not exported; checking the export list instead)"
showmount -e localhost | sed 's/^/  /'

ip=$(ip -4 -o route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") print $(i+1)}')
log "Done"
echo "NFS server : ${ip:-<this VM>}"
for s in $NFS_SHARES; do echo "Share      : ${ip:-<vm>}:$NFS_ROOT/$s"; done
echo "Space      : $(df -h --output=avail "$NFS_ROOT" | tail -1 | tr -d ' ') free on $(findmnt -no SOURCE -T "$NFS_ROOT")"
[[ -z $DATA_DISK && $(findmnt -no TARGET -T "$NFS_ROOT") == / ]] && \
  echo "NOTE       : shares are on the system disk; add a disk in Proxmox and re-run with DATA_DISK=/dev/sdX"
exit 0
