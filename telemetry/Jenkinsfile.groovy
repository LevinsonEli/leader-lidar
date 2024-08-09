
def call() {

  pipeline {
    agent any

    tools {
        maven 'MavenTool'
        jdk 'OracleJDK8'
    }

    environment {
      GIT_URL = 'git@gitlab.com:jenkins1444027/telemetry.git'

      SETTINGS_XML_ID = 'artifactory_settings_xml'
      CREDENTIALS_ID = 'artifactory_admin_credentials'
      SSH_CREDENTIALS_ID = 'ssh_to_gitlab'
      PERSONAL_ACCESS_TOKEN_ID = 'Personal_GitLab_Access_Token'
      REGISTRY_URL = 'registry.gitlab.com'
      REGISTRY_USERNAME = 'eliyahulevinson@gmail.com'
    }

    stages {
      stage('Checkout') {
        when {
          anyOf {
            branch 'feature/*'
            branch 'master'
            branch 'release/*'
          }
        }
        steps {
          script {
            println "---------------------   Checkout Stage   ---------------------"
            deleteDir()
            checkout scm
          }
        }
      }

      stage('Unit Tests') {
        when {
          anyOf {
            branch 'feature/*'
            branch 'master'
            branch 'release/*'
          }
        }
        steps {
          script {
            println "---------------------   Unit Tests Stage   ---------------------"
            sh 'mvn test'
          }
        }
      }

      stage('Build') {
        when {
          anyOf {
            branch 'feature/*'
            branch 'master'
            branch 'release/*'
          }
        }
        steps {
          script {
            println "---------------------   Build Stage   ---------------------"
            sh 'mvn clean package -DskipTests '
          }
        }
      }

      stage('E2E Tests') {
        when {
          anyOf {
            branch 'master'
            branch 'release/*'
            branch 'feature/*'
          }
        }
        steps {
          script {
            try {

            println "---------------------   E2E Tests Stage   ---------------------"
            def commitMessage = sh(script: "git log -1 --pretty=%B ${env.GIT_COMMIT}", returnStdout: true).trim()
            if ( (GIT_BRANCH.startsWith('feature/') || GIT_BRANCH.startsWith('origin/feature/')) && !commitMessage.contains('#e2e') ) {
              return
            }

            withCredentials([usernamePassword(credentialsId: "${env.CREDENTIALS_ID}", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
              configFileProvider([configFile(fileId: "${env.SETTINGS_XML_ID}", variable: 'SETTINGS_XML_FILE')]) {
                telemetry_cur = sh(script: "ls ./target | grep telemetry", returnStdout: true).trim()
                
                sh "chmod +x get_latest.sh"
                def telemetry_lst = sh(script: "./get_latest.sh telemetry 1 0 ${USERNAME} ${PASSWORD} libs-release-local", returnStdout: true).trim()
                TELEMETRY_LST = "1.0.${telemetry_lst}"

                def simulator_lst = sh(script: "./get_latest.sh simulator 0 0 ${USERNAME} ${PASSWORD} libs-snapshot-local", returnStdout: true).trim()
                simulator_snapshot_name = simulator_lst.split('/')[0]
                SIMULATOR_LST = simulator_lst.split('/')[1]

                def analytics_lst = sh(script: "./get_latest.sh analytics 1 0 ${USERNAME} ${PASSWORD} libs-release-local", returnStdout: true).trim()
                ANALYTICS_LST = "1.0.${analytics_lst}"

                sh "curl -u ${USERNAME}:${PASSWORD} -O http://artifactory:8081/artifactory/libs-snapshot-local/com/lidar/simulator/${simulator_snapshot_name}/simulator-${SIMULATOR_LST}.jar"
                sh "curl -u ${USERNAME}:${PASSWORD} -O http://artifactory:8081/artifactory/libs-release-local/com/lidar/analytics/${ANALYTICS_LST}/analytics-${ANALYTICS_LST}.jar"
                // sh "java -cp simulator-${SIMULATOR_LST}.jar:./target/${telemetry_cur}:analytics-${ANALYTICS_LST}.jar com.lidar.simulation.Simulator"

                // Devide tests file into 5 parts
                sh "chmod +x split_tests.sh"
                sh './split_tests.sh 5 tests.txt'
              }
            }

            } catch (Exception e) {
              echo "Build failed: ${e.message}"
            }
          }
        }
      }
      
      stage('Running E2E Tests in Parallel') {
        parallel {
          stage('Part 1') {
            steps {
              script {
                runE2ETestsPartly(1, SIMULATOR_LST, telemetry_cur, ANALYTICS_LST)
              }
            }
          }
          stage('Part 2') {
            steps {
              script {
                runE2ETestsPartly(2, SIMULATOR_LST, telemetry_cur, ANALYTICS_LST)
              }
            }
          }
          stage('Part 3') {
            steps {
              script {
                runE2ETestsPartly(3, SIMULATOR_LST, telemetry_cur, ANALYTICS_LST)
              }
            }
          }
          stage('Part 4') {
            steps {
              script {
                runE2ETestsPartly(4, SIMULATOR_LST, telemetry_cur, ANALYTICS_LST)
              }
            }
          }
          stage('Part 5') {
            steps {
              script {
                runE2ETestsPartly(5, SIMULATOR_LST, telemetry_cur, ANALYTICS_LST)
              }
            }
          }
        }
      }

      stage('Calculate Tag') {
        when {
          anyOf {
            branch 'release/*'
          }
        }
        
        steps {
          sshagent([env.SSH_CREDENTIALS_ID]) {
            script {
              println "---------------------   Preparing for Publish: Calculate Tag Stage   ---------------------"
              def version = GIT_BRANCH.split('/')[-1]
              MAJOR_VERSION = version.split('\\.')[0]
              MINOR_VERSION = version.split('\\.')[1]

              sh 'git fetch --tags'
              def latestPatch = sh( script: "git tag -l ${MAJOR_VERSION}.${MINOR_VERSION}.* | sort -V | tail -n1 | cut -d '.' -f 3", returnStdout: true).trim()
              if (latestPatch.length() != 0) {
                PATCH_NUMBER = latestPatch.toInteger() + 1
              } else {
                PATCH_NUMBER = 0
              }
              TAG = "${MAJOR_VERSION}.${MINOR_VERSION}.${PATCH_NUMBER}"
            }
          }
        }
      }

      stage('Set Version') {
        when {
          anyOf {
            branch 'release/*'
          }
        }
        steps {
          script {
              println "---------------------   Preparing for Publish: Set Version Stage   ---------------------"
            sh "mvn versions:set -DnewVersion=${TAG}"
            sh "mvn versions:commit"
          }
        }
      }

      stage('Publish') {
        when {
          anyOf {
            branch 'master'
            branch 'release/*'
          }
        }
        steps {
          script {
            println "---------------------   Publish Stage   ---------------------"
            withCredentials([usernamePassword(credentialsId: "${env.CREDENTIALS_ID}", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
              configFileProvider([configFile(fileId: "${env.SETTINGS_XML_ID}", variable: 'SETTINGS_XML_FILE')]) {
                sh """
                  mvn deploy -s ${SETTINGS_XML_FILE} -DskipTests -Dusername=${USERNAME} -Dpassword=${PASSWORD}
                """
              }
            }
          }
        }
      }
    
      stage('Taging') {
        when {
          anyOf {
            branch 'release/*'
          }
        }
        steps {
          script {
            println "---------------------   Taging Stage   ---------------------"
            sshagent([env.SSH_CREDENTIALS_ID]) {
              script {
                sh "git tag ${TAG}"
                sh "git push origin tag ${TAG}"
              }
            }
          }
        }
      }
    }

    post {
      success {
        emailext body: "Your last build successed",
        subject: "SUCCESS",
        to: "${env.REGISTRY_USERNAME}"
      }

      failure {
        emailext body: "Your last build failed",
        subject: "FAILURE",
        to: "${env.REGISTRY_USERNAME}"
      }
    }
  }
}


def runE2ETestsPartly(part, simulator_lst, telemetry_cur, analytics_lst) {
  if (fileExists("splited_tests/part0${part}.txt")) {
    println "---------------------   E2E Tests Part $part Stage   ---------------------"
    def part1Workspace = pwd() + "/part0$part"
    dir(part1Workspace) {
      sh "cat ../splited_tests/part0${part}.txt > tests.txt"
      sh "java -cp ../simulator-${simulator_lst}.jar:../target/${telemetry_cur}:../analytics-${analytics_lst}.jar com.lidar.simulation.Simulator"
    }
  }
}