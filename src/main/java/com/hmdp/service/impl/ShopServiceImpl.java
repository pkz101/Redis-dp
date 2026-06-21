package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 线程池
    private  static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        Shop shop = queryWithPassTrough(id);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }

/*        // 缓存击穿(互斥锁）
        shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }*/
/*        // 缓存击穿(逻辑过期）
        shop = queryWithLogicExpire(id);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }*/

        return Result.ok(shop);

    }

    // 缓存击穿逻辑过期
    private Shop queryWithLogicExpire(Long id) {
        // 1. 从redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 未命中
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = (Shop) redisData.getData();
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 未过期，返回店铺信息
            return shop;
        }

        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            // 获取锁成功，再次检查缓存
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isBlank(shopJson)) {
                return null;
            }
            redisData = JSONUtil.toBean(shopJson, RedisData.class);
            shop = (Shop) redisData.getData();
            if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                return shop;
            }


            try {
                // 开启线程，重新写入缓存
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    saveShop2Redis(id, 10L);
                });
                return shop;
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                unlock(lockKey);
            }

        }
        // 锁未获取成功，返回过期信息
        return shop;
    }


    // 互斥锁
    private Shop queryWithMutex(Long id) {
        // 1. 从redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 命中
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 命中空值（缓存穿透）
        if (shopJson != null) {
            return null;
        }

        // 3. 未命中，获取互斥锁（缓存击穿）
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 获取锁失败，休眠并重试
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // 4. Double Check
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }

            // 5. 查数据库
            shop = getById(id);
            // 不存在，缓存空值
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 6. 写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7. 释放互斥锁
            unlock(lockKey);
        }

        return shop;
    }

    // 缓存穿透
    private Shop queryWithPassTrough(Long id) {
        // 1. 从redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 命中
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 命中空值（缓存穿透）
        if (shopJson != null) {
            return null;
        }

        // 不存在， 查数据库
        Shop shop = getById(id);
        // 不存在，缓存空值
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 数据库存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;

    }



    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺ID不能为空！");
        }
        // 1. 更新数据库
        updateById(shop);
        // 2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY +  id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }

    @Override
    public Result queryShopByGeo(Double x, Double y, Integer current) {
        if (x == null || y == null) {
            return Result.fail("坐标不能为空！");
        }

        String geoKey = SHOP_GEO_KEY;

        // 检查GEO数据是否已加载
        Boolean loaded = stringRedisTemplate.hasKey("shop:geo:loaded");
        if (loaded == null || !loaded) {
            // 加载商铺坐标到Redis GEO
            loadShopGeoData(geoKey);
        }

        // GEO查询：按距离升序
        int from = (current - 1) * SystemConstants.MAX_PAGE_SIZE;
        int size = SystemConstants.MAX_PAGE_SIZE;

        // GEORADIUS 查询
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .radius(geoKey,
                        new Circle(new Point(x, y), new Distance(5000, Metrics.KILOMETERS)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .sortAscending()
                                .limit(from + size));

        if (results == null || results.getContent().isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 解析结果
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        // 手动分页
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> pagedContent;
        if (from >= content.size()) {
            pagedContent = Collections.emptyList();
        } else {
            int toIndex = Math.min(from + size, content.size());
            pagedContent = content.subList(from, toIndex);
        }

        // 提取店铺ID和距离
        List<String> ids = pagedContent.stream()
                .map(c -> c.getContent().getName())
                .collect(Collectors.toList());
        Map<String, Double> distanceMap = pagedContent.stream()
                .collect(Collectors.toMap(
                        c -> c.getContent().getName(),
                        c -> c.getDistance().getValue()
                ));

        if (ids.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 根据ID批量查询店铺
        List<Shop> shops = listByIds(ids);
        // 设置距离字段
        shops.forEach(shop -> {
            Double distance = distanceMap.get(shop.getId().toString());
            if (distance != null) {
                shop.setDistance(distance);
            }
        });

        // 按距离排序
        shops.sort(Comparator.comparingDouble(Shop::getDistance));

        return Result.ok(shops);
    }

    private void loadShopGeoData(String geoKey) {
        List<Shop> shops = query().isNotNull("x").isNotNull("y").list();
        if (shops.isEmpty()) {
            return;
        }

        List<RedisGeoCommands.GeoLocation<String>> locations = shops.stream()
                .map(s -> new RedisGeoCommands.GeoLocation<>(
                        s.getId().toString(),
                        new Point(s.getX(), s.getY())))
                .collect(Collectors.toList());

        stringRedisTemplate.opsForGeo().add(geoKey, locations);
        stringRedisTemplate.opsForValue().set("shop:geo:loaded", "1");
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id,Long expireSeconds) {
        // 1. 构建缓存数据
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);

        redisData.setExpireTime(LocalDateTime.now().plusSeconds(CACHE_SHOP_TTL));
        // 2. 写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + shop.getId(), JSONUtil.toJsonStr(redisData));
    }
}
