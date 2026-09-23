node('nexgrid-ci') {
  timeout(time: 40, unit: 'MINUTES') {
    try {
      stage('Isolation preflight') {
        sh 'bash /opt/nexgrid-ci/ci-network-check.sh'
      }
      stage('Checkout test') {
        deleteDir()
        checkout([$class: 'GitSCM', branches: [[name: '*/test']],
          userRemoteConfigs: [[url: '@REPO@', refspec: '+refs/heads/test:refs/remotes/origin/test']],
          extensions: [[$class: 'CloneOption', shallow: true, depth: 1, noTags: true, honorRefspec: true, timeout: 10]]])
      }
      stage('TEST contracts and build') {
        sh 'bash /opt/nexgrid-ci/ci-build.sh @KIND@'
        archiveArtifacts artifacts: 'artifacts/*', fingerprint: true
      }
      stage('Ready for host health gate') {
        echo 'RELEASE_ARTIFACT_READY: only the independent host broker can promote this test artifact; CI SUCCESS is not proof of live deployment.'
        currentBuild.description = 'test artifact ready; live result is recorded by host release broker'
      }
    } finally {
      deleteDir()
    }
  }
}
