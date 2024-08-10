package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private IUserService userService;

    private final StringRedisTemplate stringRedisTemplate;

    public FollowServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result follow(Long followId, Boolean isFollow) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //判断关注还是取关
        if(isFollow)
        {
            //关注
            Follow follow = new Follow();
            follow.setUserId(userId).setFollowUserId(followId);
            boolean isSuccess = save(follow);
            if(isSuccess) {
                String key = RedisConstants.USER_FOLLOW_KEY + userId;
                stringRedisTemplate.opsForSet().add(key, followId.toString());
            }
        }else {
            //取关
            boolean isSuccess = remove(new LambdaUpdateWrapper<Follow>().eq(Follow::getFollowUserId, followId)
                    .eq(Follow::getUserId, userId));
            if(isSuccess)
            {
                String key = RedisConstants.USER_FOLLOW_KEY + userId;
                stringRedisTemplate.opsForSet().remove(key, followId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followId) {
        Follow one = query().eq("follow_user_id", followId).
                eq("user_id", UserHolder.getUser().getId()).one();
        return Result.ok(one != null);
    }

    @Override
    public Result followCommons(Long id) {
        //获得当前用户的id
        Long userId = UserHolder.getUser().getId();
        //得到当前用户和目标用户关注的交集
        String key1 = RedisConstants.USER_FOLLOW_KEY + userId;
        String key2 = RedisConstants.USER_FOLLOW_KEY + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(intersect == null || intersect.isEmpty())
        {
            return Result.ok(Collections.emptyList());
        }
        List<Long> commonsUserIds = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //查询用户
        List<User> users = userService.listByIds(commonsUserIds);
        if(users == null || users.isEmpty())
        {
            return Result.fail("用户不存在");
        }
        List<UserDTO> userDTOS = users.stream().
                map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());

        return Result.ok(userDTOS);
    }
}
