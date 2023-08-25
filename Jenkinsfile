@Library('existing-build-control')
import static com.r3.build.BuildControl.killAllExistingBuildsForJob
import groovy.transform.Field

killAllExistingBuildsForJob(env.JOB_NAME, env.BUILD_NUMBER.toInteger())

boolean isReleaseBranch = (env.BRANCH_NAME =~ /^release\/.*/)
boolean isReleaseTag = (env.TAG_NAME =~ /^release-.*$/)
boolean isRelease = isReleaseTag || isReleaseBranch
String publishOptions = isRelease ? "-s --info" : "--no-daemon -s -PversionFromGit"

pipeline {
    agent { label 'standard' }

    parameters {
        booleanParam name: 'DO_PUBLISH', defaultValue: isRelease, description: 'Publish artifacts to Artifactory?'
    }

    options {
        timestamps()
        buildDiscarder(logRotator(daysToKeepStr: '14', artifactDaysToKeepStr: '14'))
    }

    triggers {
        cron (isReleaseBranch ? 'H 0 * * 1,4' : '')
    }

    environment {
        ARTIFACTORY_CREDENTIALS = credentials('artifactory-credentials')
        CORDA_ARTIFACTORY_USERNAME = "${env.ARTIFACTORY_CREDENTIALS_USR}"
        CORDA_ARTIFACTORY_PASSWORD = "${env.ARTIFACTORY_CREDENTIALS_PSW}"
        CORDA_GRADLE_SCAN_KEY = credentials('gradle-build-scans-key')
        EXECUTOR_NUMBER = "${env.EXECUTOR_NUMBER}"
        SNYK_TOKEN  = credentials('c4-ent-snyk-api-token-secret')
        JAVA_HOME="/usr/lib/jvm/java-17-amazon-corretto"
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
                expression { params.DO_PUBLISH }
                beforeAgent true
            }
            steps {
                rtServer(
                        id: 'R3-Artifactory',
                        url: 'https://software.r3.com/artifactory',
                        credentialsId: 'artifactory-credentials'
                )
                rtGradleDeployer(
                        id: 'deployer',
                        serverId: 'R3-Artifactory',
                        repo: isRelease ? 'corda-lib' : 'corda-lib-dev'
                )
                rtGradleRun(
                        usesPlugin: true,
                        useWrapper: true,
                        switches: publishOptions,
                        tasks: 'artifactoryPublish',
                        deployerId: 'deployer',
                        buildName: env.ARTIFACTORY_BUILD_NAME
                )
                rtPublishBuildInfo(
                        serverId: 'R3-Artifactory',
                        buildName: env.ARTIFACTORY_BUILD_NAME
                )
            }
        }
    }

    post {
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
