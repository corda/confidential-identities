@Library('corda-shared-build-pipeline-steps')
import static com.r3.build.BuildControl.killAllExistingBuildsForJob

killAllExistingBuildsForJob(env.JOB_NAME, env.BUILD_NUMBER.toInteger())

boolean isReleaseBranch = (env.BRANCH_NAME =~ /^release\/.*/)
boolean isReleaseTag = (env.TAG_NAME =~ /^release-.*$/)

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
        buildDiscarder(logRotator(daysToKeepStr: '14', artifactDaysToKeepStr: '14'))
    }

    environment {
        ARTIFACTORY_CREDENTIALS = credentials('artifactory-credentials')
        CORDA_ARTIFACTORY_USERNAME = "${env.ARTIFACTORY_CREDENTIALS_USR}"
        CORDA_ARTIFACTORY_PASSWORD = "${env.ARTIFACTORY_CREDENTIALS_PSW}"
        CORDA_GRADLE_SCAN_KEY = credentials('gradle-build-scans-key')
        GRADLE_USER_HOME = "/host_tmp/gradle"
        EXECUTOR_NUMBER = "${env.EXECUTOR_NUMBER}"
        SNYK_TOKEN  = credentials('c4-ent-snyk-api-token-secret')
    }

    stages {
        stage('build') {
            steps {
                sh "./gradlew clean assemble -Si"
            }
        }

        stage('Snyk Security Scan') {
            when {
                expression { isReleaseTag || isReleaseBranch }
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

        stage('Unit Tests') {
            steps {
                sh "./gradlew test -Si"
            }
        }

        stage('Integration Tests') {
            steps {
                sh "./gradlew integrationTest -Si"
            }
        }

        stage('Publish to Artifactory') {
            when {
                expression { isReleaseTag }
            }
            steps {
                sh "./gradlew artifactoryPublish -Si"
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
                if (isReleaseTag || isReleaseBranch) {
                    snykSecurityScan.generateHtmlElements()
                }
            }
        }
        cleanup {
            deleteDir() /* clean up our workspace */
        }
    }
}