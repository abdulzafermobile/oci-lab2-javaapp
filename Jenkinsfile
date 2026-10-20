pipeline {
  agent any

  triggers { githubPush() }

  // Maven is provisioned by Jenkins (Step 5) and put on PATH for every stage
  tools { maven 'maven3' }

  parameters {
    string(name: 'DEPLOY_VERSION', defaultValue: 'latest',
           description: 'latest = build and deploy this commit. A build number (e.g. 17) = redeploy that existing artifact (rollback).')
  }

  options {
    timestamps()
    disableConcurrentBuilds()
    buildDiscarder(logRotator(numToKeepStr: '20'))
  }

  environment {
    APP_HOST = '10.0.0.34'
    APP_USER = 'opc'
    SSH_OPTS = '-o StrictHostKeyChecking=accept-new -o BatchMode=yes -o ConnectTimeout=10'
  }

  stages {

    stage('Compile & test') {
      when { expression { params.DEPLOY_VERSION == 'latest' } }
      steps {
        sh 'mvn -B -ntp clean verify'
      }
      post {
        always {
          // Shows test results in Jenkins even when tests fail
          junit testResults: 'target/surefire-reports/*.xml', allowEmptyResults: true
        }
      }
    }

    stage('Package artifact') {
      when { expression { params.DEPLOY_VERSION == 'latest' } }
      steps {
        sh '''
          set -eu
          sha256sum target/app.jar | awk '{print $1}' > target/app.jar.sha256
          ls -l target/app.jar
          echo "sha256: $(cat target/app.jar.sha256)"
        '''
        // Convenience copy in Jenkins. The bucket is the real artifact store.
        archiveArtifacts artifacts: 'target/app.jar, target/app.jar.sha256', fingerprint: true
      }
    }

    stage('Upload to Object Storage') {
      when { expression { params.DEPLOY_VERSION == 'latest' } }
      steps {
        withCredentials([string(credentialsId: 'oci-par-upload', variable: 'PAR_URL')]) {
          sh '''
            set -eu
            OBJ="releases/app-${BUILD_NUMBER}.jar"
            curl -fsS -X PUT --upload-file target/app.jar        "${PAR_URL}${OBJ}"
            curl -fsS -X PUT --upload-file target/app.jar.sha256 "${PAR_URL}${OBJ}.sha256"
            echo "Uploaded ${OBJ}"
          '''
        }
      }
    }

    stage('Deploy to VM') {
      steps {
        sshagent(credentials: ['ssh-oci-vm-key']) {
          withCredentials([string(credentialsId: 'oci-par-download', variable: 'PAR_URL')]) {
            sh '''
              set -eu
              VER="${DEPLOY_VERSION:-latest}"
              if [ "$VER" = "latest" ]; then VER="$BUILD_NUMBER"; fi
              echo "Deploying artifact version $VER"

              # The PAR URL travels as an argument, never inside a logged command line.
              ssh $SSH_OPTS "$APP_USER@$APP_HOST" bash -s -- "$PAR_URL" "$VER" <<'REMOTE'
set -eu
PAR="$1"; VER="$2"
BASE=/opt/ci-lab
REL="$BASE/releases/$VER"

# 1. Download into a NEW directory. The live release is untouched.
mkdir -p "$REL"
curl -fsS --retry 3 -o "$REL/app.jar"        "${PAR}releases/app-${VER}.jar"
curl -fsS --retry 3 -o "$REL/app.jar.sha256" "${PAR}releases/app-${VER}.jar.sha256"

# 2. Verify the bytes are exactly what Jenkins uploaded
echo "$(cat "$REL/app.jar.sha256")  $REL/app.jar" | sha256sum --check --status \
  || { echo "CHECKSUM MISMATCH for version $VER"; exit 1; }
echo "build $VER deployed $(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$REL/VERSION"

# 3. Remember the current release, then switch atomically
PREV="$(readlink -f "$BASE/current" || true)"
ln -sfn "$REL" "$BASE/current.new"
mv -Tf "$BASE/current.new" "$BASE/current"

# 4. Restart and health-check. If unhealthy, switch back automatically.
sudo systemctl restart ci-lab-app
for i in 1 2 3 4 5 6 7 8 9 10; do
  if curl -fsS --max-time 3 http://127.0.0.1:8080/healthz; then echo; echo "HEALTHY"; exit 0; fi
  sleep 2
done

echo "Health check failed - recent logs:"
sudo journalctl -u ci-lab-app -n 40 --no-pager || true
if [ -n "$PREV" ] && [ -d "$PREV" ]; then
  echo "Rolling back to $PREV"
  ln -sfn "$PREV" "$BASE/current.new"
  mv -Tf "$BASE/current.new" "$BASE/current"
  sudo systemctl restart ci-lab-app
fi
exit 1
REMOTE
            '''
          }
        }
      }
    }

    stage('Verify through Nginx') {
      steps {
        sh '''
          set -eu
          curl -fsS --max-time 15 "http://$APP_HOST/healthz" | tee /dev/stderr | grep -q '"status":"ok"'
        '''
      }
    }
  }

  post {
    failure {
      echo 'Deployment failed. If the new version was unhealthy the VM switched back to the previous release automatically.'
    }
  }
}
