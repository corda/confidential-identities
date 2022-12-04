@Library('corda-shared-build-pipeline-steps')
import static com.r3.build.BuildControl.killAllExistingBuildsForJob
import groovy.transform.Field

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

boolean isReleaseBranch = (env.BRANCH_NAME = ~/^release\/.*/)
boolean isReleaseTag = (env.TAG_NAME = ~/^release-.*$/)
boolean isReleaseCandidate = (env.TAG_NAME = ~/^(release-.*(RC|HC).*)$/)

pipeline {
    agent {
        docker {
            // Our custom docker image
            image 'build-zulu-openjdk:8'
            label 'standard'
            registryUrl 'https://engineering-docker.software.r3.com/'
            registryCredentialsId 'artifactory-credentials'
            // Used to mount storage from the host as a volume to persist the cache between builds
            args '-v /tmp:/host_tmp'
            alwaysPull true
        }
    }

    options {
        timestamps()
    }

    parameters {
        choice choices: nexusIqStageChoices, description: 'NexusIQ stage for code evaluation', name: 'nexusIqStage'
    }

    environment {
        ARTIFACTORY_CREDENTIALS = credentials('artifactory-credentials')
        CORDA_ARTIFACTORY_USERNAME = "${env.ARTIFACTORY_CREDENTIALS_USR}"
        CORDA_ARTIFACTORY_PASSWORD = "${env.ARTIFACTORY_CREDENTIALS_PSW}"
        CORDA_GRADLE_SCAN_KEY = credentials('gradle-build-scans-key')
        GRADLE_USER_HOME = "/host_tmp/gradle"
        EXECUTOR_NUMBER = "${env.EXECUTOR_NUMBER}"
        MAVEN_LOCAL_PUBLISH = "${env.WORKSPACE}/${mavenLocal}"
        SNYK_TOKEN  = credentials("c4-sdk-snyk")
    }

    stages {
        stage('build') {
            steps {
                sh "./gradlew clean assemble -Si"
            }
        }

        stage('Snyk Security') {
            when {
                expression { isReleaseTag || isReleaseCandidate || isReleaseBranch }
            }
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
                script {
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
                        ).collect {
                            f -> [scanPattern: f.path]
                        }
                    }
                    nexusPolicyEvaluation(
                        failBuildOnNetworkError: true,
                        iqApplication: nexusAppId, // application *has* to exist before a build starts!
                        iqScanPatterns: fileToScan,
                        iqStage: params.nexusIqStage
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

        stage('Publish to Artifactory') {
            when {
                expression { isReleaseTag || isReleaseCandidate }
            }
            steps {
                echo "helo"
                // sh "./gradlew artifactoryPublish -Si"
            }
        }
    }
    post {
        always {
            junit '**/build/test-results/**/*.xml'
            findBuildScans()
        }
        success {
            script {
                if (isReleaseTag || isReleaseCandidate || isReleaseBranch) {
                    snykSecurityScan.generateHtmlElements()
                }
            }
        }
        cleanup {
            deleteDir() /* clean up our workspace */
        }
    }
}