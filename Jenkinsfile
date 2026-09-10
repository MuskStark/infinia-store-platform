// Jenkins pipeline for the Infinia Store Platform — the self-hosted
// counterpart of .github/workflows/ci.yml: same verification (backend
// verify, frontend tests & builds), then deploys via scripts/upgrade.sh
// when the built branch is main.
//
// Stage containers provide the toolchain (Maven/JDK 21, Node 22/Yarn 4),
// so the Jenkins agent itself only needs Docker. The GitHub workflow
// additionally smoke-builds the monitor jar; here that build happens inside
// the store/monitor Docker images during the deploy (same multi-stage
// Dockerfiles), so a broken SPA embed still fails the run — at deploy time
// instead of test time.
//
// One-time setup — DEPLOYMENT.md, "Automated deploys → Jenkins":
//   * plugins: Pipeline, Docker Pipeline, SSH Agent (remote deploys only)
//   * job: "Pipeline from SCM" pointing at this file (branch main), or a
//     multibranch job
//   * edit the environment block below to point at your production host

pipeline {
  agent any

  options {
    timestamps()
    buildDiscarder(logRotator(numToKeepStr: '30'))
    // Queue builds — a deploy in flight must never be cancelled mid-run.
    disableConcurrentBuilds()
    timeout(time: 60, unit: 'MINUTES')
  }

  triggers {
    // Out-of-the-box change detection (every ~5 min). Switch to a GitHub
    // webhook for instant builds and then remove this block, or the same
    // push may queue two builds.
    pollSCM('H/5 * * * *')
  }

  environment {
    // ---------------- deployment target (edit once) -----------------------
    // SSH host of the store server. Empty = Jenkins runs ON the production
    // host: the deploy executes scripts/upgrade.sh locally against PROD_PATH
    // (needs passwordless sudo for the Jenkins user on that script).
    PROD_HOST     = '10.5.20.83'
    PROD_USER     = 'jack'
    PROD_PATH     = '/home/jack/infinia-store-platform'
    // Jenkins "SSH Username with private key" credential for remote deploys.
    DEPLOY_KEY_ID = 'infinia-prod-deploy'
    // Split-host status monitor (ADR-011): upgraded separately by pulling the
    // CI-published GHCR image — same SSH user, its own deploy credential.
    MONITOR_HOST  = '10.5.20.84'
    MONITOR_PATH  = '/home/jack/infinia-store-platform'
    MONITOR_KEY_ID = 'infinia-monitor-deploy'
  }

  stages {
    stage('Backend (Maven, Java 21)') {
      agent {
        docker {
          image 'maven:3.9-eclipse-temurin-21'
          // Root keeps the shared cache volume writable; the named volume
          // makes repeat builds incremental.
          args '-u root -v infinia-maven-cache:/root/.m2'
          reuseNode true
        }
      }
      steps {
        sh './mvnw -B verify'
      }
      post {
        always {
          junit allowEmptyResults: true,
                testResults: '**/target/*-reports/TEST-*.xml'
        }
      }
    }

    stage('Frontend (Yarn 4)') {
      agent {
        docker {
          // Full (non-slim) image: the drift check needs git.
          image 'node:22-bookworm'
          args '-u root -v infinia-yarn-cache:/root/.yarn/berry'
          reuseNode true
        }
      }
      environment {
        YARN_ENABLE_GLOBAL_CACHE = '1'
        YARN_GLOBAL_FOLDER       = '/root/.yarn/berry'
      }
      steps {
        sh 'corepack enable'
        sh 'yarn install --immutable'
        sh '''
          yarn workspace @infinia/store-web gen:api
          git diff --exit-code -- store-web/src/api/schema.d.ts
        '''
        sh 'yarn ui:test'
        sh 'yarn web:test'
        sh 'yarn monitor:test'
        sh 'yarn web:build'
        sh 'yarn monitor:build'
      }
    }

    stage('Deploy (production)') {
      when { branch 'main' }
      steps {
        script {
          if (env.PROD_HOST?.trim()) {
            echo "Deploying ${env.GIT_COMMIT?.take(12)} to ${env.PROD_USER}@${env.PROD_HOST}:${env.PROD_PATH} over SSH"
            sshagent(credentials: [env.DEPLOY_KEY_ID]) {
              sh '''
                ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new \
                    "$PROD_USER@$PROD_HOST" \
                    "cd '$PROD_PATH' && bash scripts/upgrade.sh --ref '$GIT_COMMIT'"
              '''
            }
            // Monitor host pulls the CI-published GHCR image pinned to this
            // commit; the image for a just-pushed commit may lag a few
            // minutes behind (GitHub Actions publish), the next push catches up.
            echo "Refreshing monitor ${env.PROD_USER}@${env.MONITOR_HOST}:${env.MONITOR_PATH} (GHCR image pull)"
            sshagent(credentials: [env.MONITOR_KEY_ID]) {
              sh '''
                ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new \
                    "$PROD_USER@$MONITOR_HOST" \
                    "cd '$MONITOR_PATH' && git fetch origin --prune && git checkout -f --detach '$GIT_COMMIT' && sudo -n docker compose -f docker-compose.monitor.yml pull && sudo -n docker compose -f docker-compose.monitor.yml up -d"
              '''
            }
          } else {
            echo "Deploying ${env.GIT_COMMIT?.take(12)} locally (Jenkins shares the production host)"
            sh 'sudo -n bash "$PROD_PATH/scripts/upgrade.sh" --ref "$GIT_COMMIT"'
          }
        }
      }
    }
  }
}
