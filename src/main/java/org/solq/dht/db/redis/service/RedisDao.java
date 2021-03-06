package org.solq.dht.db.redis.service;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.solq.dht.db.redis.anno.CacheStrategy;
import org.solq.dht.db.redis.anno.LockStrategy;
import org.solq.dht.db.redis.anno.StoreStrategy;
import org.solq.dht.db.redis.event.IRedisEvent;
import org.solq.dht.db.redis.event.RedisEventCode;
import org.solq.dht.db.redis.model.CursorCallBack;
import org.solq.dht.db.redis.model.IRedisDao;
import org.solq.dht.db.redis.model.IRedisEntity;
import org.solq.dht.db.redis.model.IRedisMBean;
import org.solq.dht.db.redis.model.LockCallBack;
import org.solq.dht.db.redis.model.TxCallBack;
import org.solq.dht.db.redis.service.manager.RedisDataSourceManager;
import org.solq.dht.db.redis.service.manager.RedisPersistentManager;
import org.solq.dht.db.redis.service.ser.Jackson3JsonRedisSerializer;
import org.solq.dht.db.redis.service.ser.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;

import com.google.common.cache.CacheBuilder;

import redis.clients.jedis.Protocol;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.util.SafeEncoder;

/**
 * 基础 http://shift-alt-ctrl.iteye.com/category/277626 <br>
 * 备份问题 http://my.oschina.net/freegeek/blog/324410
 * 
 * @author solq
 */
@SuppressWarnings("unchecked")
public class RedisDao<T extends IRedisEntity> implements IRedisDao<String, T>, IRedisMBean, InitializingBean {
    private static Logger logger = LoggerFactory.getLogger(RedisDao.class);

    @Autowired
    protected RedisDataSourceManager redisDataSourceManager;

    protected RedisConnectionFactory cf;
    protected RedisTemplate<String, ?> redis;

    protected Jackson3JsonRedisSerializer<T> valueRedisSerializer;
    protected Class<T> entityClass;

    protected LockStrategy lockStrategy;

    protected CacheStrategy cacheStrategy;

    protected StoreStrategy storeStrategy;

    private ConcurrentMap<String, T> cache = new ConcurrentHashMap<>();

    private String owner;

    public void afterPropertiesSet() {
	if (entityClass == null) {
	    // 执行期获取java 泛型类型,class 必须要有硬文件存在
	    entityClass = (Class<T>) ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
	}

	lockStrategy = entityClass.getAnnotation(LockStrategy.class);
	cacheStrategy = entityClass.getAnnotation(CacheStrategy.class);
	storeStrategy = getClass().getAnnotation(StoreStrategy.class);

	// dataSource 指定来源
	if (cf == null) {
	    if (redisDataSourceManager == null) {
		throw new RuntimeException("redis redisDataSourceManager is null");
	    }
	    if (storeStrategy == null) {
		throw new RuntimeException("redis storeStrategy is null");
	    }
	    cf = redisDataSourceManager.getRedisConnection(storeStrategy.dataSource());
	}
	if (cf == null) {
	    throw new RuntimeException("RedisConnectionFactory is null");
	}

	// 默认锁策略设置
	if (lockStrategy == null) {
	    lockStrategy = new LockStrategy() {

		@Override
		public Class<? extends Annotation> annotationType() {
		    return LockStrategy.class;
		}

		@Override
		public int times() {
		    return 25;
		}

		@Override
		public long sleepTime() {
		    return 250;
		}

		@Override
		public long expires() {
		    return 1000 * 10;
		}
	    };
	}

	// 缓存设置
	if (cacheStrategy != null) {
	    int length = cacheStrategy.lenth();
	    long expires = cacheStrategy.expires();

	    CacheBuilder<Object, Object> builder = CacheBuilder.newBuilder();
	    builder.initialCapacity(16);
	    builder.concurrencyLevel(16);
	    // LRU
	    if (length > 0) {
		builder.maximumSize(length);
	    }

	    // TIME OUT
	    if (expires > 0) {
		builder.expireAfterAccess(expires, TimeUnit.MILLISECONDS);
		builder.expireAfterWrite(expires, TimeUnit.MILLISECONDS);
	    }

	    cache = (ConcurrentMap<String, T>) builder.build();
	}

	valueRedisSerializer = new Jackson3JsonRedisSerializer<T>((Class<T>) entityClass);

	// 设置redis tpl
	if (redis == null) {
	    redis = new RedisTemplate<String, Object>();
	    redis.setEnableDefaultSerializer(false);
	    redis.setConnectionFactory(cf);
	    redis.setKeySerializer(StringSerializer.INSTANCE);
	    // redis.setValueSerializer(valueRedisSerializer);
	    redis.afterPropertiesSet();
	}
	// 注册监听
	owner = getClass() + "_" + entityClass + "_" + UUID.randomUUID().toString();
	RedisPersistentManager.register(owner, this);
    }

    public static <T extends IRedisEntity> RedisDao<T> of(Class<T> entityClass, RedisConnectionFactory cf) {
	RedisDao<T> result = new RedisDao<T>();
	result.entityClass = entityClass;
	result.cf = cf;
	result.afterPropertiesSet();
	return result;
    }

    public static <T extends IRedisEntity> RedisDao<T> of(Class<T> entityClass, RedisConnectionFactory cf, RedisTemplate<String, ?> redis) {
	RedisDao<T> result = new RedisDao<T>();
	result.entityClass = entityClass;
	result.cf = cf;
	result.redis = redis;
	result.afterPropertiesSet();
	return result;
    }

    @Override
    public T findOneForCache(String key) {
	if (cacheStrategy == null) {
	    return findOne(key);
	}

	T result = (T) cache.get(key);
	if (result == null) {
	    result = findOne(key);
	    if (result != null) {
		cache.put(key, result);
	    }
	}
	return result;
    }

    @Override
    public T findOne(String key) {
	T result = null;
	try {
	    result = redis.execute(new RedisCallback<T>() {
		@Override
		public T doInRedis(RedisConnection connection) throws DataAccessException {
		    byte[] rawKey = keySerializer(key);
		    byte[] result = connection.get(rawKey);
		    return valueDeserialize(result);
		}
	    }, true);
	} catch (Exception e) {
	    if (!checkConnectException(e)) {
		throw e;
	    }
	}
	return result;
    }

    @Override
    public void saveOrUpdateSync(T... entitys) {
	for (T entity : entitys) {
	    boolean ok = false;
	    try {
		redis.execute(new RedisCallback<T>() {
		    @Override
		    public T doInRedis(RedisConnection connection) throws DataAccessException {
			byte[] rawKey = keySerializer(entity.toId());
			connection.set(rawKey, valueSerializer(entity));
			if (cacheStrategy != null) {
			    cache.put(entity.toId(), entity);
			}
			return null;
		    }
		}, true);
		ok = true;
	    } catch (Exception e) {
		if (checkConnectException(e)) {
		    ok = false;
		} else {
		    throw e;
		}
	    } finally {
		// 连接失败处理
		if (!ok && this.storeStrategy.retryDelay() > 0) {
		    RedisPersistentManager.putAction(RedisSaveOrUpdateAction.of(this, entity, true), this.storeStrategy.retryDelay());
		}
	    }
	}
    }

    @Override
    public void saveOrUpdate(T... entitys) {
	for (T entity : entitys) {
	    if (cacheStrategy != null) {
		cache.put(entity.toId(), entity);
	    }
	    RedisPersistentManager.putAction(RedisSaveOrUpdateAction.of(this, entity, false), this.storeStrategy.delay());
	}
    }

    @Override
    public Set<String> keys(String pattern) {
	try {
	    return redis.keys(pattern);
	} catch (Exception e) {
	    if (checkConnectException(e)) {
		return Collections.emptySet();
	    } else {
		throw e;
	    }
	}
    }

    @Override
    public List<T> query(String pattern) {
	try {
	    Set<String> ids = redis.keys(pattern);
	    List<T> result = new ArrayList<>(ids.size());
	    for (String id : ids) {
		result.add(findOne(id));
	    }
	    return result;
	} catch (Exception e) {
	    if (checkConnectException(e)) {
		return Collections.EMPTY_LIST;
	    } else {
		throw e;
	    }
	}
    }

    @Override
    public void cursor(String pattern, CursorCallBack<T> cb) {
	try {
	    Set<String> ids = redis.keys(pattern);
	    for (String id : ids) {
		cb.exec(findOne(id));
	    }
	} catch (Exception e) {
	    if (!checkConnectException(e)) {
		throw e;
	    }
	}
    }

    @Override
    public void remove(String... keys) {
	for (String key : keys) {
	    cache.remove(key);
	    RedisPersistentManager.putAction(RedisRemoveAction.of(this, key, false), this.storeStrategy.delay());
	}
    }

    @Override
    public void removeSync(String... keys) {
	for (String key : keys) {
	    try {
		cache.remove(key);
		redis.delete(key);
	    } catch (Exception e) {
		if (checkConnectException(e) && this.storeStrategy.retryDelay() > 0) {
		    RedisPersistentManager.putAction(RedisRemoveAction.of(this, key, true), this.storeStrategy.retryDelay());
		} else {
		    throw e;
		}
	    }
	}
    }

    private boolean checkConnectException(Exception e) {
	boolean flag = (e instanceof java.net.ConnectException || e instanceof RedisConnectionFailureException || e instanceof JedisConnectionException);
	if (flag) {
	    logger.error("{}", e);
	}
	return flag;
    }

    @Override
    public void clearCache(String... keys) {
	for (String key : keys) {
	    cache.remove(key);
	}
    }

    @Override
    public void clearAllCache() {
	cache.clear();
    }

    @Override
    public <R> R lock(String key, LockCallBack<R> callBack) {
	String lockKey = "_clock_" + key;
	if (lock(lockKey)) {
	    try {
		return callBack.exec(key);
	    } finally {
		unLock(lockKey);
	    }
	}
	throw new RuntimeException("time out");
    }

    @Override
    public T tx(String key, TxCallBack<T> callBack) {

	String lockKey = "_clock_" + key;
	if (lock(lockKey)) {
	    T entity = null;
	    try {
		entity = findOne(key);
		entity = callBack.exec(entity);

	    } finally {
		if (entity != null) {
		    saveOrUpdateSync(entity);
		}
		unLock(lockKey);
	    }
	    return entity;
	}

	throw new RuntimeException("time out");
    }

    @Override
    public void send(IRedisEvent msg, String... channels) {
	String json = object2Json(msg);
	byte[] bytes = valueSerializer(RedisEventCode.of(msg.getClass(), json));
	for (String channel : channels) {
	    this.redis.convertAndSend(channel, bytes);
	}
    }

    @Override
    public void destroy() {
	if (cf instanceof DisposableBean) {
	    try {
		((DisposableBean) cf).destroy();
	    } catch (Exception e) {
		e.printStackTrace();
	    }
	}
    }

    @Override
    public Boolean move(String key, int dbIndex) {
	return redis.move(key, dbIndex);
    }

    @Override
    public boolean rename(String oldKey, String newKey) {
	try {
	    redis.rename(oldKey, newKey);
	    return true;
	} catch (JedisConnectionException e) {
	    return false;
	}
    }

    @Override
    public boolean exists(String key) {
	return redis.hasKey(key);
    }

    @Override
    public void expire(String key, long timeout, TimeUnit unit) {
	redis.expire(key, timeout, unit);
    }

    @Override
    public DataType type(String key) {
	return redis.type(key);
    }

    @Override
    public long getDbUseSize() {
	return redis.execute(new RedisCallback<Long>() {
	    @Override
	    public Long doInRedis(RedisConnection connection) throws DataAccessException {
		return connection.dbSize();
	    }
	});
    }

    ////////////////////// 内部方法//////////////////////////

    private boolean lock(String lockKey) {
	// 处理次数
	int times = lockStrategy.times();
	// 下次请求等侍时间
	long sleepTime = lockStrategy.sleepTime();
	// 有效时间
	final long expires = lockStrategy.expires() / 1000;
	// 最大请求等侍时间
	final long maxWait = 2 * 1000;

	final byte[] key = keySerializer(lockKey);
	int i = 0;
	while (times-- >= 0) {
	    Object ok = redis.execute(new RedisCallback<Object>() {

		@Override
		public Object doInRedis(RedisConnection connection) throws DataAccessException {
		    Object _ok = connection.execute(
			    Protocol.Command.SET.name(),
			    key, 
			    new byte[] { 0 }, 
			    SafeEncoder.encode("EX"), 
			    Protocol.toByteArray(expires), 
			    SafeEncoder.encode("NX"));

		    return _ok;
		}
	    });
	    if (ok != null) {
		return true;
	    }
	    long t = Math.min(maxWait, sleepTime * i);
	    _await(t);
	    i++;
	}

	return false;
    }

    private void unLock(String lockKey) {
	redis.delete(lockKey);
    }

    private void _await(long sleepTime) {
	try {
	    Thread.sleep(sleepTime);
	} catch (InterruptedException e) {
	    e.printStackTrace();
	}
    }

    private byte[] keySerializer(String key) {
	return StringSerializer.INSTANCE.serialize(key);
    }

    private byte[] valueSerializer(Object entity) {
	return this.valueRedisSerializer.serialize(entity);
    }

    private T valueDeserialize(byte[] bytes) {
	return this.valueRedisSerializer.deserialize(bytes);
    }

    private String object2Json(Object entity) {
	return Jackson3JsonRedisSerializer.object2Json(entity);
    }

    // get set

    public void setRedisDataSourceManager(RedisDataSourceManager redisDataSourceManager) {
	this.redisDataSourceManager = redisDataSourceManager;
    }

    public Class<T> getEntityClass() {
	return entityClass;
    }

    public String getOwner() {
	return owner;
    }

}
