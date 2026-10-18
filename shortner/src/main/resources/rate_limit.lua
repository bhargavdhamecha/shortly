-- Atomic multi-bucket token bucket.
--
-- KEYS[i]      bucket key (one per layered limit, e.g. per-minute and per-day)
-- ARGV[1]      cost: 1 consumes a token, 0 only reports whether one is available
-- ARGV[2i]     capacity of bucket i
-- ARGV[2i + 1] refill rate of bucket i, in tokens per second
--
-- Returns { allowed, remaining, retryAfterSeconds }.
--
-- Every bucket is checked before any is charged, so a request rejected by the daily
-- limit does not silently burn a token from the per-minute limit.

local cost = tonumber(ARGV[1])

-- Redis' own clock: instances disagree about wall time, and skew would corrupt refills.
local time = redis.call('TIME')
local now = tonumber(time[1]) + tonumber(time[2]) / 1000000

local count = #KEYS
local tokens = {}
local capacities = {}
local rates = {}
local allowed = 1
local retry_after = 0

for i = 1, count do
    local capacity = tonumber(ARGV[2 * i])
    local rate = tonumber(ARGV[2 * i + 1])
    capacities[i] = capacity
    rates[i] = rate

    local state = redis.call('HMGET', KEYS[i], 'tokens', 'ts')
    local available = tonumber(state[1])
    local updated_at = tonumber(state[2])
    if available == nil or updated_at == nil then
        available = capacity
        updated_at = now
    end

    -- Lazy refill: no background job, just elapsed time since the last write.
    local elapsed = now - updated_at
    if elapsed < 0 then
        elapsed = 0
    end
    available = math.min(capacity, available + elapsed * rate)
    tokens[i] = available

    if available < 1 then
        allowed = 0
        local wait = math.ceil((1 - available) / rate)
        if wait > retry_after then
            retry_after = wait
        end
    end
end

local remaining = -1
for i = 1, count do
    local value = tokens[i]
    if allowed == 1 and cost > 0 then
        value = value - cost
    end
    -- Written even when rejected: the refilled state and timestamp still move forward.
    redis.call('HSET', KEYS[i], 'tokens', tostring(value), 'ts', tostring(now))
    -- Idle buckets expire, so memory tracks active callers rather than all callers.
    redis.call('PEXPIRE', KEYS[i], math.ceil((capacities[i] / rates[i]) * 1000) + 1000)

    local left = math.floor(value)
    if remaining < 0 or left < remaining then
        remaining = left
    end
end

return { allowed, remaining, retry_after }
