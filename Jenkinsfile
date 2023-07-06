@Library('existing-build-control')
import static com.r3.build.BuildControl.killAllExistingBuildsForJob

killAllExistingBuildsForJob(env.JOB_NAME, env.BUILD_NUMBER.toInteger())

boolean isReleaseBranch = (env.BRANCH_NAME =~ /^release\/.*/)
boolean isReleaseTag = (env.TAG_NAME =~ /^release-.*$/)

pipeline {
    agent {
        dockerfile {
            filename '.ci/Dockerfile'
        }
    }

    options {
        timestamps()
        buildDiscarder(logRotator(daysToKeepStr: '14', artifactDaysToKeepStr: '14'))
    }

    environment {
        EXECUTOR_NUMBER = "${env.EXECUTOR_NUMBER}"
        SNYK_TOKEN  = credentials('c4-ent-snyk-api-token-secret')
    }

    stages {

       stage('build') {
            steps {
                script {
                    def modulesToScan = ['workflows']
                    modulesToScan.each {
                        module ->
                            snykSecurityScan("${env.SNYK_TOKEN}", "--sub-project=$module --configuration-matching='^runtimeClasspath\$' --prune-repeated-subdependencies --debug --target-reference='${env.BRANCH_NAME}' --project-tags=Branch='${env.BRANCH_NAME.replaceAll(" [ ^ 0 - 9 | a - z | A - Z] + ","_ ")}'", false, true)
                    }
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