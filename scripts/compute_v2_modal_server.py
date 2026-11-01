#!/usr/bin/env python3
"""Bounded real-model processes. Execute and verify run in separate fresh processes."""
import base64
from contextlib import contextmanager
import fcntl
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import threading
import time

ROOT=Path(os.environ.get('COMPUTE_WORK_ROOT','/work'))
MODELS=Path(os.environ.get('COMPUTE_MODEL_ROOT','/models'))
SOURCE=Path(__file__).resolve().parent
INSTALLATIONS=(SOURCE/'compute_v2_model_installations.json').read_bytes()
BINDING=('taskNo','generation','inputHash','taskClass','runtimeKey','modelRevision','workload','seed')
RUNTIMES={
    'bk-sdm-tiny-image-v1':('compute_v2_image_video.py','bk-sdm-tiny','result.png',6144,2),
    'bk-sdm-tiny-video-v1':('compute_v2_image_video.py','bk-sdm-tiny','result.mp4',6144,2),
    'll-smollm2-135m-instruct-v1':('compute_v2_text_audio.py','smollm2-135m-instruct','tokens.json',1536,2),
    'ft-smollm2-135m-lora-v1':('compute_v2_text_audio.py','smollm2-135m-instruct','adapter.zip',2560,2),
    'sp-whisper-tiny-v1':('compute_v2_text_audio.py','whisper-tiny','transcript.json',1024,2),
    'phone-minilm-wasm-v1':('compute_v2_phone_embedding.py','phone-minilm','embedding.f32',512,1),
}
WORKER_TOKEN=os.environ['COMPUTE_MODAL_WORKER_TOKEN']
VALIDATOR_TOKEN=os.environ['COMPUTE_MODAL_VALIDATOR_TOKEN']
RESOURCE=threading.Condition()
USED_MEMORY=0
USED_CPU=0
ACTIVE={}

@contextmanager
def task_operation(root):
    with (root/'operation.lock').open('ab') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        yield

def stop_process(process):
    if process.poll() is None:
        try:os.killpg(process.pid,signal.SIGTERM)
        except ProcessLookupError:pass
        try:process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            try:os.killpg(process.pid,signal.SIGKILL)
            except ProcessLookupError:pass
            process.wait()

def encoded(value):
    return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()

def binding(task):
    return hashlib.sha256(encoded({key:task[key] for key in BINDING})).hexdigest()

def run_process(operation,task,folder,result_file,record_key,timeout_seconds):
    global USED_MEMORY,USED_CPU
    module,model,_,memory,cpu=RUNTIMES[task['runtimeKey']]
    with RESOURCE:
        while USED_MEMORY+memory>6144 or USED_CPU+cpu>4:
            RESOURCE.wait()
        USED_MEMORY+=memory;USED_CPU+=cpu
    process=None
    try:
        previous=folder/(operation+'-process.json')
        if previous.exists():
            status=json.loads(previous.read_bytes())
            if status.get('exitCode')!=0:raise RuntimeError('RECOVERY_REQUIRED')
            return status
        task_file=folder/'task.json';task_file.write_bytes(encoded(task))
        command=[sys.executable,'-I','-B',str(SOURCE/module),operation,'--task-file',str(task_file),
                 '--model-root',str(MODELS/model),'--artifact-dir',str(folder),'--result-file',str(result_file)]
        previous.write_bytes(encoded({'operation':operation,'taskNo':task['taskNo'],'generation':task['generation'],'startedAt':time.time()}))
        with (folder/(operation+'.stdout')).open('wb') as log:
            process=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
            with RESOURCE:ACTIVE[record_key]={'pid':process.pid,'operation':operation,'taskNo':task['taskNo'],'generation':task['generation']}
            status={'operation':operation,'taskNo':task['taskNo'],'generation':task['generation'],'pid':process.pid,'startedAt':time.time()}
            (folder/(operation+'-process.json')).write_bytes(encoded(status))
            try:
                code=process.wait(timeout=timeout_seconds)
            except subprocess.TimeoutExpired:
                stop_process(process);code=process.returncode
            status.update(exitCode=code,exitedAt=time.time())
            (folder/(operation+'-process.json')).write_bytes(encoded(status))
        if code!=0:raise RuntimeError(operation.upper()+'_MODEL_PROCESS_FAILED')
        return status
    finally:
        if process is not None:stop_process(process)
        with RESOURCE:
            ACTIVE.pop(record_key,None);USED_MEMORY-=memory;USED_CPU-=cpu;RESOURCE.notify_all()

class Handler(BaseHTTPRequestHandler):
    def log_message(self,format,*args):
        print(json.dumps({'httpStatus':args[1] if len(args)>1 else None,'path':self.path}),flush=True)

    def reply(self,status,value):
        raw=encoded(value);self.send_response(status);self.send_header('Content-Type','application/json')
        self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)

    def authorized(self,validator=False):
        expected=VALIDATOR_TOKEN if validator else WORKER_TOKEN
        return hmac.compare_digest(self.headers.get('Authorization',''),'Bearer '+expected)

    def do_GET(self):
        if self.path=='/health':
            available=[key for key,value in RUNTIMES.items() if (SOURCE/value[0]).is_file() and (MODELS/value[1]).is_dir()]
            with RESOURCE:state={'status':'ready','modelDigest':'sha256:'+hashlib.sha256(INSTALLATIONS).hexdigest(),
                                'runtimes':available,'memoryMiB':USED_MEMORY,'cpuThreads':USED_CPU,'activeProcesses':list(ACTIVE.values())}
            self.reply(200,state);return
        self.reply(404,{'error':'NOT_FOUND'})

    def do_POST(self):
        if self.path not in ('/execute','/verify','/status'):
            self.reply(404,{'error':'NOT_FOUND'});return
        if not self.authorized(validator=self.path!='/execute'):
            self.reply(403,{'error':'FORBIDDEN'});return
        length=int(self.headers.get('Content-Length','0'))
        if not 0<length<=48*1024*1024:
            self.reply(413,{'error':'REQUEST_TOO_LARGE'});return
        try:
            body=json.loads(self.rfile.read(length));task=body['task']
            timeout_seconds=int(body.get('executionTimeoutSec',3600))
            if not 1<=timeout_seconds<=172800:raise ValueError('EXECUTION_TIMEOUT_INVALID')
            task={key:task[key] for key in BINDING}
            key=binding(task);runtime=RUNTIMES[task['runtimeKey']]
            root=ROOT/key;root.mkdir(parents=True,exist_ok=True)
            if self.path=='/status':
                with RESOURCE:active=[value for name,value in ACTIVE.items() if name.endswith(key)]
                result={'taskNo':task['taskNo'],'generation':task['generation'],'taskBindingHash':key,'activeProcesses':active}
                for operation in ('execute','verify'):
                    record=root/operation/(operation+'-process.json')
                    result[operation]=json.loads(record.read_bytes()) if record.exists() else None
                self.reply(200,result);return
            operation='execute' if self.path=='/execute' else 'verify'
            folder=root/operation;folder.mkdir(exist_ok=True);result_file=folder/'result.json'
            if operation=='execute':
                if task['runtimeKey']=='phone-minilm-wasm-v1':raise ValueError('PHONE_EXECUTION_REQUIRED')
                with task_operation(root):
                    bundle_path=folder/'bundle.json'
                    if not bundle_path.exists():
                        run_process(operation,task,folder,result_file,operation+':'+key,timeout_seconds)
                        result=json.loads(result_file.read_bytes());artifact=folder/runtime[2]
                        bundle={'result':result,'artifactBase64':base64.b64encode(artifact.read_bytes()).decode()}
                        bundle_path.write_bytes(encoded(bundle))
                    raw=bundle_path.read_bytes()
                self.reply(200,{'bundleBase64':base64.b64encode(raw).decode(),'resultHash':hashlib.sha256(raw).hexdigest(),'taskBindingHash':key});return
            raw=base64.b64decode(body['bundleBase64'],validate=True)
            if not 0<len(raw)<=32*1024*1024:raise ValueError('BUNDLE_SIZE_INVALID')
            result_hash=hashlib.sha256(raw).hexdigest();bundle=json.loads(raw)
            result=bundle['result']
            if result['taskBindingHash']!=key or result['artifact']['path']!=runtime[2]:raise ValueError('ARTIFACT_BINDING_INVALID')
            artifact=base64.b64decode(bundle['artifactBase64'],validate=True)
            if hashlib.sha256(artifact).hexdigest()!=result['artifact']['sha256']:raise ValueError('ARTIFACT_HASH_INVALID')
            with task_operation(root):
                verified_file=folder/'verified.json'
                if verified_file.exists():
                    verified=json.loads(verified_file.read_bytes())
                    if verified['resultHash']!=result_hash:raise ValueError('RESULT_CONFLICT')
                    self.reply(200,verified);return
                frozen=folder/'bundle.sha256'
                if frozen.exists() and frozen.read_text()!=result_hash:raise ValueError('RESULT_CONFLICT')
                frozen.write_text(result_hash)
                (folder/runtime[2]).write_bytes(artifact);result_file.write_bytes(encoded(result))
                run_process(operation,task,folder,result_file,operation+':'+key,timeout_seconds)
                verified={'verified':True,'taskBindingHash':key,'resultHash':result_hash}
                verified_file.write_bytes(encoded(verified));self.reply(200,verified)
        except (KeyError,ValueError,TypeError) as error:
            self.reply(422,{'error':str(error)[:200]})
        except RuntimeError as error:
            self.reply(503,{'error':str(error)[:200]})

if __name__=='__main__':
    ROOT.mkdir(parents=True,exist_ok=True)
    ThreadingHTTPServer(('0.0.0.0',11442),Handler).serve_forever()
