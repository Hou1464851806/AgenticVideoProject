local capacity = tonumber(ARGV[1])
local period = tonumber(ARGV[2])
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local state = redis.call('HMGET', KEYS[1], 'balance', 'updated')
local balance = capacity
if state[1] then
    balance = math.min(capacity, tonumber(state[1]) + math.max(0, now - tonumber(state[2])) / period)
end
local wait = 0
if balance >= 1 then
    balance = balance - 1
else
    wait = math.ceil((1 - balance) * period)
end
redis.call('HSET', KEYS[1], 'balance', tostring(balance), 'updated', now)
redis.call('PEXPIRE', KEYS[1], capacity * period)
return wait
