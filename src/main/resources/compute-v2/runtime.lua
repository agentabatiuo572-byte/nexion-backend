-- Monetary JSON is never rewritten or used in arithmetic. Resource admission reads only frozen class/model/device fields.
local p = KEYS[1]
local op = ARGV[1]
local function key(s) return p .. s end
local function err(s) return redis.error_reply('COMPUTE_V2_' .. s) end
local function admissionError(now,backlog,capacity)
  if redis.call('GET',key('ready')) ~= '1' or redis.call('EXISTS',key('last-freeze')) == 0 then return 'RECOVERING' end
  for _,name in ipairs({'pending-age','outgoing-age'}) do
    local oldest = redis.call('ZRANGE',key(name),0,0,'WITHSCORES')
    if #oldest>0 and tonumber(now)-tonumber(oldest[2])>=tonumber(backlog) then return 'PERSISTENCE_BACKLOG' end
  end
  if redis.call('HLEN',key('pending'))>=tonumber(capacity) then return 'PERSISTENCE_CAPACITY' end
  return nil
end
local function freezeActive(user,ending)
  if redis.call('EXISTS',key('active:' .. user)) == 0 then return '' end
  local id = tostring(redis.call('INCR',key('window-sequence')))
  redis.call('RENAME',key('active:' .. user),key('frozen:' .. id))
  redis.call('ZADD',key('frozen-index'),ending,id)
  redis.call('ZREM',key('active-index'),user)
  return id
end
local function prune(task, replacing, postedTtl)
  local state = redis.call('HGET',key('states'),task)
  if state ~= 'COMPLETED' and state ~= 'FAILED' and state ~= 'HISTORICAL' then return false end
  if redis.call('HEXISTS',key('executors'),task) == 1
      or tonumber(redis.call('HGET',key('pending-count'),task) or '0') > 0 then return false end
  local dev = redis.call('HGET',key('task-devices'),task)
  if not replacing and dev and redis.call('HGET',key('devices'),dev) == task then return false end
  local gen = redis.call('HGET',key('generation'),task)
  local request = redis.call('HGET',key('task-requests'),task)
  if request then
    redis.call('DEL',key('request-event:' .. request))
    redis.call('EXPIRE',key('request:' .. request),postedTtl or '600')
  end
  if gen then
    for _,kind in ipairs({'ISSUED','STARTED','VERIFIED','FAILED','FENCED','EXITED'}) do
      redis.call('HDEL',key('lifecycle-events'),task .. ':' .. gen .. ':' .. kind)
    end
  end
  for _,name in ipairs({'owners','instances','generation','states','snapshots','issued-events','start-events','completed','failed-events','fenced-events','task-devices','pending-count','verification-id','task-requests','posted-requests'}) do
    redis.call('HDEL',key(name),task)
  end
  redis.call('ZREM',key('leases'),task)
  redis.call('ZREM',key('queue'),task)
  return true
end
if op == 'initialize' then
  redis.call('SETNX',key('last-freeze'),ARGV[2])
  redis.call('SETNX',key('recovered-at'),ARGV[3] or '0')
  return '1'
end
if op == 'summary' then
  if redis.call('GET',key('ready')) ~= '1' or redis.call('EXISTS',key('last-freeze')) == 0 then return err('RECOVERING') end
  local result = {}
  for _,name in ipairs({'pending-usdt','pending-nex','pending-completions','pending-nex-completions'}) do
    table.insert(result,redis.call('HGET',key(name),ARGV[2]) or '0')
  end
  for _,payload in ipairs(redis.call('HVALS',key('pending-user:' .. ARGV[2]))) do table.insert(result,payload) end
  return result
end
if op == 'ready' then
  return redis.call('GET',key('ready')) == '1' and redis.call('EXISTS',key('last-freeze')) == 1 and '1' or '0'
end
if op == 'admission' then
  return ARGV[5]=='1' and not admissionError(ARGV[2],ARGV[3],ARGV[4]) and '1' or '0'
end
if op == 'block' then
  for i=3,#ARGV do redis.call('HSET',key('blocked-events'),ARGV[i],ARGV[2]) end
  return '1'
end
if op == 'prune' then return prune(ARGV[2],false,ARGV[3]) and '1' or '0' end
if op == 'retire' then
  local task = redis.call('HGET',key('devices'),ARGV[2])
  if not task then return '1' end
  local state = redis.call('HGET',key('states'),task)
  if (state ~= 'COMPLETED' and state ~= 'FAILED') or redis.call('HEXISTS',key('executors'),task) == 1 then return err('DEVICE_NOT_DRAINED') end
  redis.call('HDEL',key('devices'),ARGV[2])
  prune(task,true,ARGV[3])
  return '1'
end
if op == 'restore' then
  local task,dev,owner,gen,kind = ARGV[2],ARGV[3],ARGV[4],ARGV[5],ARGV[6]
  local request = owner .. ':' .. dev .. ':' .. ARGV[11]
  redis.call('HSET',key('task-requests'),task,request)
  if ARGV[13] == '1' and kind == 'ISSUED' then redis.call('HSET',key('posted-requests'),task,'1') end
  local oldgen = tonumber(redis.call('HGET',key('device-generation'),dev) or '0')
  redis.call('HSET',key('lifecycle-events'),task .. ':' .. gen .. ':' .. kind,ARGV[8])
  if tonumber(gen) < oldgen then
    redis.call('HSET',key('owners'),task,owner)
    redis.call('HSET',key('task-devices'),task,dev)
    redis.call('HSET',key('generation'),task,gen)
    redis.call('HSET',key('snapshots'),task,ARGV[7])
    redis.call('HSET',key('states'),task,'HISTORICAL')
    if kind == 'ISSUED' then redis.call('HSET',key('issued-events'),task,ARGV[8])
    elseif kind == 'STARTED' then redis.call('HSET',key('start-events'),task,ARGV[8])
    elseif kind == 'VERIFIED' then redis.call('HSET',key('completed'),task,ARGV[8]) end
    redis.call('ZREM',key('queue'),task)
    return '1'
  end
  if tonumber(gen) >= oldgen then
    redis.call('HSET',key('device-generation'),dev,gen)
    redis.call('HSET',key('devices'),dev,task)
  end
  redis.call('HSET',key('owners'),task,owner)
  redis.call('HSET',key('task-devices'),task,dev)
  redis.call('HSET',key('generation'),task,gen)
  redis.call('HSET',key('snapshots'),task,ARGV[7])
  redis.call('SET',key('request:' .. request),task .. '|' .. gen,'EX',ARGV[12] or '604800')
  if kind == 'ISSUED' then
    redis.call('HSET',key('issued-events'),task,ARGV[8])
    if ARGV[13] ~= '1' then redis.call('SET',key('request-event:' .. request),ARGV[8],'EX',ARGV[12] or '604800') end
    if redis.call('HEXISTS',key('states'),task) == 0 then
      redis.call('HSET',key('states'),task,'QUEUED')
      redis.call('ZADD',key('queue'),ARGV[9],task)
    end
  elseif kind == 'STARTED' then
    redis.call('HSET',key('start-events'),task,ARGV[8])
    local state = redis.call('HGET',key('states'),task)
    if not state or state == 'QUEUED' then
      redis.call('HSET',key('states'),task,'RECOVERING')
      redis.call('HSET',key('executors'),task,'RECOVERY_REQUIRED')
      redis.call('SADD',key('permits'),task .. ':' .. gen)
      redis.call('ZADD',key('leases'),ARGV[10],task)
      redis.call('ZREM',key('queue'),task)
    end
  elseif kind == 'VERIFIED' then
    redis.call('HSET',key('states'),task,'COMPLETED')
    redis.call('HSET',key('completed'),task,ARGV[8])
    redis.call('ZREM',key('queue'),task)
  elseif kind == 'FAILED' or kind == 'FENCED' then
    redis.call('HSET',key(kind == 'FENCED' and 'fenced-events' or 'failed-events'),task,ARGV[8])
    if redis.call('HGET',key('states'),task) ~= 'COMPLETED' then
      redis.call('HSET',key('states'),task,kind == 'FENCED' and 'RECOVERING' or 'FAILED')
      redis.call('ZREM',key('queue'),task)
    end
  elseif kind == 'EXITED' then
    redis.call('HDEL',key('executors'),task)
    redis.call('SREM',key('permits'),task .. ':' .. gen)
    redis.call('ZREM',key('leases'),task)
    if redis.call('HGET',key('states'),task) ~= 'COMPLETED' then redis.call('HSET',key('states'),task,'FAILED') end
  end
  return '1'
end
if op == 'reserve' then
  if redis.call('GET',key('ready')) ~= '1' then return err('RECOVERING') end
  local old = redis.call('GET',key('request:' .. ARGV[2]))
  if old then
    local task,gen = string.match(old,'(.+)|(%d+)$')
    return {task,gen}
  end
  if ARGV[13]=='0' then return err('ADMISSION_PAUSED') end
  local blocked = admissionError(ARGV[6],ARGV[8],ARGV[9])
  if blocked then return err(blocked) end
  local dev = ARGV[3]
  local current = redis.call('HGET',key('devices'),dev)
  if current then
    local state = redis.call('HGET',key('states'),current)
    if state ~= 'COMPLETED' and state ~= 'FAILED' then return err('DEVICE_BUSY') end
    if redis.call('HEXISTS',key('executors'),current) == 1 then return err('EXECUTION_NOT_EXITED') end
    prune(current,true,ARGV[14])
  elseif redis.call('HLEN',key('devices')) >= tonumber(ARGV[7]) then return err('MANAGED_CAPACITY') end
  local gen = redis.call('HINCRBY',key('device-generation'),dev,1)
  local task = ARGV[4]
  redis.call('SET',key('request:' .. ARGV[2]),task .. '|' .. tostring(gen),'EX',ARGV[11] or '604800')
  redis.call('HSET',key('devices'),dev,task)
  redis.call('HSET',key('owners'),task,ARGV[5])
  redis.call('HSET',key('instances'),task,ARGV[12] or 'INSTANCE')
  redis.call('HSET',key('task-devices'),task,dev)
  redis.call('HSET',key('task-requests'),task,ARGV[2])
  redis.call('HSET',key('generation'),task,tostring(gen))
  redis.call('HSET',key('states'),task,'ISSUING')
  redis.call('HSET',key('snapshots'),task,ARGV[10])
  return {task,tostring(gen)}
end
if op == 'start' then
  local task = ARGV[2]
  if redis.call('GET',key('ready')) ~= '1' then return err('RECOVERING') end
  if redis.call('HGET',key('generation'),task) ~= ARGV[3] then return err('STALE_GENERATION') end
  local state = redis.call('HGET',key('states'),task)
  if state == 'STARTING' or state == 'COMPUTING' then
    if redis.call('HGET',key('executors'),task) ~= ARGV[4] then return err('LEASE_OWNED') end
    return redis.call('HGET',key('start-events'),task)
  end
  if state ~= 'QUEUED' then return err('NOT_QUEUED') end
  if ARGV[11]~='1' then return err('ADMISSION_PAUSED') end
  local blocked = admissionError(ARGV[8],ARGV[9],ARGV[10])
  if blocked then return err(blocked) end
  if redis.call('SCARD',key('permits')) >= tonumber(ARGV[7]) then return '' end
  local policy = ARGV[12] and cjson.decode(ARGV[12]) or {}
  if policy.enabled then
    local function phone(snapshot)
      return snapshot.taskClass == 'EM' and snapshot.modelName == 'all-MiniLM-L6-v2'
          and (snapshot.device.deviceType == 'PHONE' or snapshot.device.deviceType == 'MOBILE')
    end
    local function snapshot(id) return cjson.decode(redis.call('HGET',key('snapshots'),id)) end
    local function cost(value)
      if phone(value) then return 'phone',0,0 end
      local configured = policy.costs[value.taskClass]
      if not configured or configured.modelName ~= value.modelName then return nil end
      return value.taskClass == 'EM' and 'em' or 'modal',configured.memoryMiB,configured.cpuThreads
    end
    local current = snapshot(task)
    local pool,memory,cpu = cost(current)
    if not pool then return err('RESOURCE_MODEL_UNCONFIGURED') end
    local modalMemory,serverCpu,emCount = 0,0,0
    -- Permits are bounded by executionCapacity (at most six). Failed, fenced or
    -- expired work still consumes its frozen resources until the existing EXITED event removes it.
    for _,permit in ipairs(redis.call('SMEMBERS',key('permits'))) do
      local activeTask = string.match(permit,'(.+):%d+$')
      local activePool,activeMemory,activeCpu = cost(snapshot(activeTask))
      if not activePool then return err('RESOURCE_MODEL_UNCONFIGURED') end
      serverCpu = serverCpu + activeCpu
      if activePool == 'em' then emCount = emCount + 1
      elseif activePool == 'modal' then modalMemory = modalMemory + activeMemory end
    end
    if pool == 'em' and emCount >= policy.emMaxConcurrent then return '' end
    if pool ~= 'phone' and serverCpu + cpu > policy.serverCpuThreads then return '' end
    if pool == 'modal' and modalMemory + memory > policy.modalMemoryMiB then return '' end
    if pool ~= 'phone' then
      -- Preserve FIFO among cloud work. Phone owner reservations do not block
      -- that queue, and they cannot borrow server inference RAM/CPU by changing their label.
      local cloudHead = nil
      for _,queued in ipairs(redis.call('ZRANGE',key('queue'),0,-1)) do
        if not phone(snapshot(queued)) then cloudHead = queued; break end
      end
      if cloudHead ~= task then return '' end
    end
  else
    local head = redis.call('ZRANGE',key('queue'),0,0)
    if #head == 0 or head[1] ~= task then return '' end
  end
  redis.call('SADD',key('permits'),task .. ':' .. ARGV[3])
  redis.call('HSET',key('executors'),task,ARGV[4])
  redis.call('ZADD',key('leases'),ARGV[5],task)
  redis.call('HSET',key('states'),task,'STARTING')
  redis.call('HSET',key('start-events'),task,ARGV[6])
  return ARGV[6]
end
if op == 'renew' then
  local state = redis.call('HGET',key('states'),ARGV[2])
  if redis.call('HGET',key('generation'),ARGV[2]) ~= ARGV[3]
      or redis.call('HGET',key('executors'),ARGV[2]) ~= ARGV[4]
      or (state ~= 'COMPUTING' and state ~= 'VERIFYING')
      or tonumber(redis.call('ZSCORE',key('leases'),ARGV[2]) or '0') <= tonumber(ARGV[5]) then return err('LEASE_STALE') end
  redis.call('ZADD',key('leases'),ARGV[6],ARGV[2])
  return '1'
end
if op == 'verify' then
  local task = ARGV[2]
  if redis.call('HGET',key('generation'),task) ~= ARGV[3] then return err('STALE_GENERATION') end
  local prior = redis.call('HGET',key('verification-id'),task)
  if prior then
    if prior ~= ARGV[4] then return err('VERIFICATION_CONFLICT') end
    return '0'
  end
  if redis.call('HGET',key('states'),task) ~= 'COMPUTING'
      or tonumber(redis.call('ZSCORE',key('leases'),task) or '0') <= tonumber(ARGV[5]) then return err('LEASE_STALE') end
  redis.call('HSET',key('states'),task,'VERIFYING')
  redis.call('HSET',key('verification-id'),task,ARGV[4])
  return '1'
end
if op == 'cancel' then
  local task = ARGV[2]
  if redis.call('HGET',key('generation'),task) ~= ARGV[3] then return '' end
  if redis.call('HEXISTS',key('executors'),task) == 1 then return '' end
  local state = redis.call('HGET',key('states'),task)
  local prior = redis.call('HGET',key('staged'),ARGV[4]) or redis.call('HGET',key('lifecycle-events'),ARGV[4])
  if prior and (state == 'FAILING' or state == 'FAILED') then return prior end
  if state ~= 'ISSUING' and state ~= 'QUEUED' then return '' end
  redis.call('HSET',key('states'),task,'FAILING')
  redis.call('ZREM',key('queue'),task)
  redis.call('HSET',key('staged'),ARGV[4],ARGV[5])
  return ARGV[5]
end
if op == 'stage' then
  local task = ARGV[2]
  if redis.call('HGET',key('generation'),task) ~= ARGV[3] then return err('STALE_GENERATION') end
  local existing = redis.call('HGET',key('staged'),ARGV[4]) or redis.call('HGET',key('lifecycle-events'),ARGV[4])
  if existing then return existing end
  local state = redis.call('HGET',key('states'),task)
  if ARGV[6] == 'VERIFIED' then
    if state ~= 'VERIFYING' or tonumber(redis.call('ZSCORE',key('leases'),task) or '0') <= tonumber(ARGV[7]) then return err('LEASE_STALE') end
    if ARGV[8] ~= '' then
      local daily = key('daily:' .. ARGV[8])
      local owner = redis.call('GET',daily)
      if owner and owner ~= ARGV[4] then ARGV[5] = ARGV[9]
      elseif not owner then redis.call('SET',daily,ARGV[4],'EX',ARGV[10]) end
    end
    redis.call('HSET',key('states'),task,'VERIFYING')
  elseif ARGV[6] == 'FENCED' then
    if state ~= 'COMPUTING' and state ~= 'STARTING' and state ~= 'VERIFYING' then return err('NOT_COMPUTING') end
    if redis.call('HEXISTS',key('staged'),task .. ':' .. ARGV[3] .. ':VERIFIED') == 1 then return err('VERIFICATION_ACK_UNKNOWN') end
    if tonumber(redis.call('ZSCORE',key('leases'),task) or '0') > tonumber(ARGV[7]) then return err('LEASE_NOT_EXPIRED') end
    redis.call('HSET',key('states'),task,'RECOVERING')
  elseif ARGV[6] == 'FAILED' then
    if state == 'COMPLETED' or state == 'RECOVERING'
        or redis.call('HEXISTS',key('staged'),task .. ':' .. ARGV[3] .. ':VERIFIED') == 1 then return err('TERMINAL_CONFLICT') end
    redis.call('HSET',key('states'),task,'FAILING')
  elseif ARGV[6] == 'EXITED' then
    if state ~= 'COMPLETED' and state ~= 'FAILED' and state ~= 'RECOVERING' then return err('NOT_TERMINAL') end
  end
  redis.call('HSET',key('staged'),ARGV[4],ARGV[5])
  return ARGV[5]
end
if op == 'outgoing' then
  local prior = redis.call('HGET',key('outgoing'),ARGV[2])
  if prior then return prior end
  if redis.call('HLEN',key('outgoing')) >= tonumber(ARGV[5]) then return err('SEND_CAPACITY') end
  redis.call('HSET',key('outgoing'),ARGV[2],ARGV[3])
  redis.call('ZADD',key('outgoing-age'),ARGV[4],ARGV[2])
  return ARGV[3]
end
if op == 'apply' then
  local id, task, gen, kind = ARGV[2],ARGV[3],ARGV[4],ARGV[5]
  local seen = key('seen:' .. id)
  local prior = redis.call('GET',seen)
  if not prior then
    local pending = redis.call('HGET',key('pending'),id)
    if pending then
      if pending ~= ARGV[6] then return err('EVENT_PAYLOAD_CONFLICT') end
      prior = 'pending:' .. ARGV[15]
      redis.call('SET',seen,prior)
    end
  end
  if prior then
    if string.sub(prior,-64) ~= ARGV[15] then return err('EVENT_PAYLOAD_CONFLICT') end
    redis.call('HDEL',key('outgoing'),id)
    redis.call('ZREM',key('outgoing-age'),id)
    return '0'
  end
  if redis.call('HLEN',key('pending')) >= tonumber(ARGV[9]) then return err('PERSISTENCE_CAPACITY') end
  if kind == 'VERIFIED' then
    -- Stay within exact Lua comparison range; the actual addition remains Redis signed integer arithmetic.
    local usdt = tonumber(redis.call('HGET',key('pending-usdt'),ARGV[11]) or '0') + tonumber(ARGV[12])
    local nex = tonumber(redis.call('HGET',key('pending-nex'),ARGV[11]) or '0') + tonumber(ARGV[13])
    if usdt > 9007199254740991 or nex > 9007199254740991 then return err('MONEY_CAPACITY') end
  end
  redis.call('SET',seen,'pending:' .. ARGV[15])
  redis.call('HSET',key('lifecycle-events'),id,ARGV[6])
  redis.call('HSET',key('pending'),id,ARGV[6])
  redis.call('HINCRBY',key('pending-count'),task,1)
  redis.call('ZADD',key('pending-age'),ARGV[7],id)
  local user,ending = ARGV[11],tonumber(ARGV[16])
  local active = tonumber(redis.call('HGET',key('active-end'),user) or tostring(ending))
  if ending > active then
    freezeActive(user,active)
    active = ending
  end
  redis.call('HSET',key('active-end'),user,tostring(active))
  redis.call('ZADD',key('active-index'),active,user)
  redis.call('HSET',key('active:' .. user),id,ARGV[6])
  if kind == 'VERIFIED' then
    redis.call('HSET',key('pending-user:' .. ARGV[11]),id,ARGV[6])
    redis.call('HINCRBY',key('pending-usdt'),ARGV[11],ARGV[12])
    redis.call('HINCRBY',key('pending-nex'),ARGV[11],ARGV[13])
    redis.call('HINCRBY',key('pending-completions'),ARGV[11],1)
    if ARGV[13] ~= '0' then redis.call('HINCRBY',key('pending-nex-completions'),ARGV[11],1) end
  end
  local current = redis.call('HGET',key('generation'),task)
  if current == gen and redis.call('HGET',key('devices'),ARGV[17]) == task
      and redis.call('HGET',key('device-generation'),ARGV[17]) == gen then
    local state = redis.call('HGET',key('states'),task)
    if kind == 'ISSUED' and state == 'ISSUING' then
      redis.call('HSET',key('states'),task,'QUEUED')
      redis.call('ZADD',key('queue'),ARGV[8],task)
    elseif kind == 'STARTED' and (state == 'STARTING' or state == 'QUEUED') then
      redis.call('HSET',key('states'),task,'COMPUTING')
      redis.call('ZREM',key('queue'),task)
      redis.call('HSET',key('snapshots'),task,ARGV[14])
    elseif kind == 'VERIFIED' and redis.call('HEXISTS',key('failed-events'),task) == 0
        and redis.call('HEXISTS',key('fenced-events'),task) == 0 then
      redis.call('HSET',key('states'),task,'COMPLETED')
      redis.call('HSET',key('completed'),task,ARGV[6])
      redis.call('ZREM',key('queue'),task)
    elseif kind == 'FAILED' and state ~= 'COMPLETED' then
      redis.call('HSET',key('failed-events'),task,ARGV[6])
      redis.call('HSET',key('states'),task,'FAILED')
      redis.call('ZREM',key('queue'),task)
    elseif kind == 'FENCED' and state ~= 'COMPLETED' then
      redis.call('HSET',key('fenced-events'),task,ARGV[6])
      redis.call('HSET',key('states'),task,'RECOVERING')
    elseif kind == 'EXITED' then
      redis.call('SREM',key('permits'),task .. ':' .. gen)
      redis.call('HDEL',key('executors'),task)
      redis.call('ZREM',key('leases'),task)
      if state == 'RECOVERING' then redis.call('HSET',key('states'),task,'FAILED') end
    end
  end
  redis.call('HDEL',key('staged'),id)
  redis.call('HDEL',key('outgoing'),id)
  redis.call('ZREM',key('outgoing-age'),id)
  return '1'
end
if op == 'daily' then
  local current = redis.call('GET',key('daily:' .. ARGV[2]))
  if current then return current == ARGV[3] and '1' or '0' end
  redis.call('SET',key('daily:' .. ARGV[2]),ARGV[3],'EX',ARGV[4])
  return '1'
end
if op == 'freeze' then
  local users = redis.call('ZRANGEBYSCORE',key('active-index'),'-inf',ARGV[2],'LIMIT',0,200)
  local first = ''
  for _,user in ipairs(users) do
    local ending = redis.call('ZSCORE',key('active-index'),user)
    local id = freezeActive(user,ending)
    if first == '' then first = id end
  end
  redis.call('SET',key('last-freeze'),ARGV[2])
  return first
end
if op == 'persisted' then
  for i = 3,#ARGV,9 do
    redis.call('HDEL',key('blocked-events'),ARGV[i])
    local payload = redis.call('HGET',key('pending'),ARGV[i])
    if payload then
      redis.call('HDEL',key('pending'),ARGV[i])
      redis.call('HDEL',key('pending-user:' .. ARGV[i+1]),ARGV[i])
      redis.call('HINCRBY',key('pending-count'),ARGV[i+4],-1)
      redis.call('ZREM',key('pending-age'),ARGV[i])
      if ARGV[i+2] ~= '0' then redis.call('HINCRBY',key('pending-usdt'),ARGV[i+1],'-' .. ARGV[i+2]) end
      if ARGV[i+3] ~= '0' then
        redis.call('HINCRBY',key('pending-nex'),ARGV[i+1],'-' .. ARGV[i+3])
        redis.call('HINCRBY',key('pending-nex-completions'),ARGV[i+1],-1)
      end
      if ARGV[i+5] == '1' then redis.call('HINCRBY',key('pending-completions'),ARGV[i+1],-1) end
    end
    redis.call('SET',key('seen:' .. ARGV[i]),'persisted:' .. ARGV[i+6],'EX',ARGV[2])
    if ARGV[i+7] == 'ISSUED' and redis.call('HEXISTS',key('generation'),ARGV[i+4]) == 1 then redis.call('HSET',key('posted-requests'),ARGV[i+4],'1') end
    if redis.call('HEXISTS',key('posted-requests'),ARGV[i+4]) == 1 then
      redis.call('EXPIRE',key('request:' .. ARGV[i+8]),ARGV[2])
      redis.call('EXPIRE',key('request-event:' .. ARGV[i+8]),ARGV[2])
    end
  end
  return '1'
end
return err('UNKNOWN_OPERATION')
