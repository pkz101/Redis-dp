package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.PasswordEncoder;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import com.hmdp.utils.JwtUtils;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        // 2. 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 3. 保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //
        // 4. 发送验证码（模拟）
        log.debug("发送短信验证码成功，验证码：{}", code);
        // 返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }

        // 2. 判断登录方式
        String code = loginForm.getCode();
        String password = loginForm.getPassword();

        User user;
        if (StrUtil.isNotBlank(code)) {
            // 验证码登录
            user = loginByCode(phone, code);
        } else if (StrUtil.isNotBlank(password)) {
            // 密码登录
            user = loginByPassword(phone, password);
        } else {
            return Result.fail("登录参数错误！");
        }

        if (user == null) {
            return Result.fail("登录失败！");
        }

        // 生成jwt令牌
        String token = JwtUtils.createToken(user.getId(),3000*60);

        // 4. 将UserDTO存入redis
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        String userJson = JSONUtil.toJsonStr(userDTO);
        stringRedisTemplate.opsForValue().set(LOGIN_USER_KEY + token, userJson, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 5. 返回token
        return Result.ok(token);
    }

    private User loginByCode(String phone, String code) {
        // 1. 校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (StrUtil.isBlank(cacheCode) || !cacheCode.equals(code)) {
            return null;
        }
        // 2. 删除验证码
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);
        // 3. 查询用户，不存在则自动注册
        User user = query().eq("phone", phone).one();
        if (user == null) {
            user = createUserWithPhone(phone);
        }
        return user;
    }

    private User loginByPassword(String phone, String password) {
        // 1. 查询用户
        User user = query().eq("phone", phone).one();
        if (user == null) {
            return null;
        }
        // 2. 校验密码
        if (!PasswordEncoder.matches(user.getPassword(), password)) {
            return null;
        }
        return user;
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }

    @Override
    public Result logout(String token) {
        if (StrUtil.isNotBlank(token)) {
            stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        }
        return Result.ok();
    }

    @Override
    public Result me() {
        UserDTO userDTO = UserHolder.getUser();
        return Result.ok(userDTO);
    }

    @Override
    public Result sign() {
        // 1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2. 获取日期
        LocalDate now = LocalDate.now();
        // 3. 拼接key
        String key = USER_SIGN_KEY + userId + ":" + now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        // 4. 签到
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /**
     * 统计当前用户当月的连续签到次数
     * 从当天开始往前计算连续签到的天数，遇到未签到则停止
     * 使用Redis的BITFIELD命令批量获取签到数据，通过位运算统计连续签到天数
     *
     * @return Result对象，包含当月连续签到次数
     */
    @Override
    public Result signCount() {
        // 1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2. 获取日期
        LocalDate now = LocalDate.now();
        // 3. 拼接key
        String key = USER_SIGN_KEY + userId + ":" + now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        // 4. 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5. 使用BITFIELD命令获取本月截止今天的所有签到记录
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6. 通过位运算统计连续签到次数
        int count = 0;
        while ((num & 1) != 0) {
            count++;
            num >>>= 1;
        }
        return Result.ok(count);
    }
}
