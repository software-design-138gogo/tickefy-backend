-- KEYS[1] = stockKey  (tickefy:inventory:available:{ttId})
-- KEYS[2] = userKey   (tickefy:inventory:user-limit:{userId}:{ttId})
-- ARGV[1] = qty
-- ARGV[2] = perUserLimit  (-1 = unlimited)
local qty   = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
if limit >= 0 then
  local owned = tonumber(redis.call('GET', KEYS[2]) or '0')
  if owned + qty > limit then return -1 end
end
local available = tonumber(redis.call('GET', KEYS[1]) or '0')
if available < qty then return -2 end
redis.call('DECRBY', KEYS[1], qty)
redis.call('INCRBY', KEYS[2], qty)
return 1
