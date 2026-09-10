import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition

def j = Jenkins.get()
def marker = new File(j.rootDir, 'nexgrid-release-v1-jobs-configured')
if (!j.isUseSecurity() || j.numExecutors != 0) throw new IllegalStateException('CONTROLLER_SECURITY_REQUIRED')
def repositories = [backend:'nexion-backend', pc:'nexion-frontend-pc', uniapp:'nexion-frontend-uniapp']
def queued = new File(j.rootDir, 'nexgrid-release-v1-initial-builds-queued')
def queueInitial = {
    if (queued.exists()) return
    repositories.each { kind, repo ->
        def job = j.getItemByFullName("nexgrid-${kind}-main")
        if (job == null || !job.definition.script.contains('RELEASE_ARTIFACT_READY')) {
            throw new IllegalStateException('RELEASE_JOB_REQUIRED')
        }
        if (!job.isBuilding() && !job.isInQueue() && job.scheduleBuild2(0) == null) {
            throw new IllegalStateException('INITIAL_BUILD_NOT_QUEUED')
        }
    }
    queued.setText('three initial main artifact builds scheduled\n', 'UTF-8')
    println('NEXGRID_RELEASE_ARTIFACT_BUILDS_QUEUED jobs=3 host_auto=HELD')
}
if (marker.exists()) { queueInitial(); return }
def template = new File('/opt/nexgrid-ci/main.pipeline.groovy').getText('UTF-8')
if (!template.contains('RELEASE_ARTIFACT_READY') || template.contains('DEPLOYMENT_HELD')) {
    throw new IllegalStateException('RELEASE_TEMPLATE_REQUIRED')
}
def originals = [:]
repositories.each { kind, repo ->
    def job = j.getItemByFullName("nexgrid-${kind}-main")
    if (job == null || job.isBuilding() || job.isInQueue()) throw new IllegalStateException('IDLE_EXISTING_JOB_REQUIRED')
    if (!(job.definition instanceof CpsFlowDefinition) || !job.definition.script.contains('DEPLOYMENT_HELD')) {
        throw new IllegalStateException('EXPECTED_OLD_HOLD_REQUIRED')
    }
    originals[kind] = [definition: job.definition, description: job.description]
}
try {
repositories.each { kind, repo ->
    def job = j.getItemByFullName("nexgrid-${kind}-main")
    def script = template.replace('@KIND@', kind).replace('@REPO@', "https://github.com/agentabatiuo572-byte/${repo}.git")
    job.setDefinition(new CpsFlowDefinition(script, true))
    job.setDescription('main TEST CI; independent host broker verifies health/rollback before promotion. CI SUCCESS alone does not mean deployed.')
    job.save()
}
marker.setText('main release v1 configured; host auto deployment remains disabled until operator activation\n', 'UTF-8')
} catch (Exception failure) {
    originals.each { kind, previous ->
        def job = j.getItemByFullName("nexgrid-${kind}-main")
        job.setDefinition(previous.definition)
        job.setDescription(previous.description)
        job.save()
    }
    throw failure
}
queueInitial()
