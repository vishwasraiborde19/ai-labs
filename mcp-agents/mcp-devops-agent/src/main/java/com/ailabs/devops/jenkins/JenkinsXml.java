package com.ailabs.devops.jenkins;

/** config.xml documents for pipeline jobs and credentials. */
public final class JenkinsXml {

    private JenkinsXml() {}

    /** Pipeline job whose Jenkinsfile is stored in the job itself, run in the Groovy sandbox. */
    public static String inlinePipeline(String description, String jenkinsfile) {
        return """
                <?xml version='1.1' encoding='UTF-8'?>
                <flow-definition plugin="workflow-job">
                  <description>%s</description>
                  <keepDependencies>false</keepDependencies>
                  <properties/>
                  <definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps">
                    <script>%s</script>
                    <sandbox>true</sandbox>
                  </definition>
                  <disabled>false</disabled>
                </flow-definition>
                """.formatted(esc(description), esc(jenkinsfile));
    }

    /** Pipeline job that reads its Jenkinsfile from git. */
    public static String scmPipeline(String description, String gitUrl, String branch, String credentialsId, String scriptPath) {
        return """
                <?xml version='1.1' encoding='UTF-8'?>
                <flow-definition plugin="workflow-job">
                  <description>%s</description>
                  <keepDependencies>false</keepDependencies>
                  <properties/>
                  <definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition" plugin="workflow-cps">
                    <scm class="hudson.plugins.git.GitSCM" plugin="git">
                      <configVersion>2</configVersion>
                      <userRemoteConfigs>
                        <hudson.plugins.git.UserRemoteConfig>
                          <url>%s</url>
                          <credentialsId>%s</credentialsId>
                        </hudson.plugins.git.UserRemoteConfig>
                      </userRemoteConfigs>
                      <branches>
                        <hudson.plugins.git.BranchSpec>
                          <name>*/%s</name>
                        </hudson.plugins.git.BranchSpec>
                      </branches>
                      <doGenerateSubmoduleConfigurations>false</doGenerateSubmoduleConfigurations>
                      <submoduleCfg class="empty-list"/>
                      <extensions/>
                    </scm>
                    <scriptPath>%s</scriptPath>
                    <lightweight>true</lightweight>
                  </definition>
                  <disabled>false</disabled>
                </flow-definition>
                """.formatted(esc(description), esc(gitUrl), esc(credentialsId == null ? "" : credentialsId),
                esc(branch), esc(scriptPath));
    }

    public static String usernamePassword(String id, String description, String username, String password) {
        return """
                <com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl>
                  <scope>GLOBAL</scope>
                  <id>%s</id>
                  <description>%s</description>
                  <username>%s</username>
                  <password>%s</password>
                </com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl>
                """.formatted(esc(id), esc(description), esc(username), esc(password));
    }

    public static String secretText(String id, String description, String secret) {
        return """
                <org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl>
                  <scope>GLOBAL</scope>
                  <id>%s</id>
                  <description>%s</description>
                  <secret>%s</secret>
                </org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl>
                """.formatted(esc(id), esc(description), esc(secret));
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
