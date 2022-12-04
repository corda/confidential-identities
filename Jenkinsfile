@Library('corda-shared-build-pipeline-steps')
import static com.r3.build.BuildControl.killAllExistingBuildsForJob

killAllExistingBuildsForJob(env.JOB_NAME, env.BUILD_NUMBER.toInteger())

@Field
String mavenLocal = 'tmp/mavenlocal'

def nexusDefaultIqStage = "build"


/**
 * make sure calculated default value of NexusIQ stage is first in the list
 * thus making it default for the `choice` parameter
 */
def nexusIqStageChoices = [nexusDefaultIqStage].plus(
                [
                        'develop',
                        'build',
                        'stage-release',
                        'release',
                        'operate'
                ].minus([nexusDefaultIqStage]))

pipeline {
    agent {
        dockerfile {
            filename '.ci/Dockerfile'
        }
    }
    options { timestamps() }

    parameters {
        choice choices: nexusIqStageChoices, description: 'NexusIQ stage for code evaluation', name: 'nexusIqStage'
    }


    environment {
        EXECUTOR_NUMBER = "${env.EXECUTOR_NUMBER}"
    }

    stages {

       stage('build') {
            steps {
                sh "./gradlew assemble -Si"
            }
        }
        
       stage('Snyk Security') {
           // when { branch pattern: "release\/.+", comparator: "REGEXP"}
            steps {
                script {
                    // Invoke Snyk for each Gradle sub project we wish to scan
                    def modulesToScan = ['workflows']
                    modulesToScan.each { module ->
                        snykSecurityScan("${env.SNYK_API_KEY}", "--sub-project=$module --configuration-matching='^runtimeClasspath\$' --prune-repeated-subdependencies --debug --target-reference='${env.BRANCH_NAME}' --project-tags=Branch='${env.BRANCH_NAME.replaceAll("[^0-9|a-z|A-Z]+","_")}'")
                    }
                }
            }
        }

        stage('Local Publish') {
            steps {
                script {
                    sh 'rm -rf $MAVEN_LOCAL_PUBLISH'
                    sh 'mkdir -p $MAVEN_LOCAL_PUBLISH'
                    sh './gradlew publishToMavenLocal -Dmaven.repo.local="${MAVEN_LOCAL_PUBLISH}"' 
                    sh 'ls -lR "${MAVEN_LOCAL_PUBLISH}"'

                }
            }
        }

        stage('Sonatype Check') {
            steps {
                
              script{
                    def props = readProperties file: 'gradle.properties'
                    version = props['version']
                    groupId = props['group']
                    def artifactId = 'ci-workflows'
                    nexusAppId = "${groupId}-${artifactId}-${version}"
                    echo "${groupId}-${artifactId}-${version}"                  
              }
                        
                dir(mavenLocal) {                        
                        script {
                            fileToScan = findFiles(
                                excludes: '**/*-javadoc.jar',
                                glob: '**/*.jar, **/*.zip'
                            ).collect { f -> [scanPattern: f.path] }
                        }
                        nexusPolicyEvaluation(
                            failBuildOnNetworkError: true,
                            iqApplication: nexusAppId, // application *has* to exist before a build starts!
                            iqScanPatterns: fileToScan,
                            iqStage:  params.nexusIqStage
                        )
                }
             }
        }

        stage('Unit Tests') {
            steps {
                sh "./gradlew clean test -Si"
            }
        }

        stage('Integration Tests') {
            steps {
                sh "./gradlew integrationTest -Si"
            }
        }
    }

    post {
        always {
            junit '**/build/test-results/**/*.xml'
        }
        cleanup {
            deleteDir() /* clean up our workspace */
        }
    }
}
