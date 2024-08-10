package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("blog不存在");
        }
        fillOtherBlogFiled(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(this::fillOtherBlogFiled);
        return Result.ok(records);
    }

    private void fillOtherBlogFiled(Blog blog) {
        User user = userService.getById(blog.getUserId());
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        String likedKey = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        if(UserHolder.getUser() != null) {
            Double score = stringRedisTemplate.opsForZSet().score(likedKey, UserHolder.getUser().getId().toString());
            blog.setIsLike(score != null);
        }
    }

    @Override
    public Result likeBlog(Long id) {
        //查看当前用户是否点过赞
        String likedKey = RedisConstants.BLOG_LIKED_KEY + id;
        Double isLiked = stringRedisTemplate.opsForZSet().score(likedKey, UserHolder.getUser().getId().toString());
        if(isLiked != null){
            //如果已经点赞过那就取消点赞
            cancelLiked(id, likedKey);
            return Result.ok();
        }
        //没点赞, 就点赞
        liked(id, likedKey);
        return Result.ok();
    }

    @Override
    public Result queryBlogLikesById(Long id) {
        //查询top5的点赞用户
        String likedKey = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(likedKey, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //解析用户id
        String idStr = String.join(",", top5);
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        //根据id查用户
        List<UserDTO> userDTOS = userService.query().in("id", ids).
                last("ORDER BY FIELD(id, " + idStr + ")").list().stream().
                map(user -> BeanUtil.copyProperties(user, UserDTO.class)).
                collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result queryUserBlog(Long id, Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("上传失败");
        }
        //查询笔记作者的所有粉丝 select * from tb_follow where
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        if(follows == null || follows.isEmpty()){
            return Result.ok();
        }
        //推送给所有粉丝的ids
        for(Follow follow : follows){
            //获得推送Key
            String key = RedisConstants.FEED_KEY + follow.getUserId().toString();
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }

        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryFollowBlog(Long lastId, Integer offset) {
        //从redis中获取收到的博客id
        String key = RedisConstants.FEED_KEY + UserHolder.getUser().getId().toString();
        Set<ZSetOperations.TypedTuple<String>> idStr =
                stringRedisTemplate.opsForZSet().
                        reverseRangeByScoreWithScores(key, 0, lastId, offset, 2);
        if(idStr == null || idStr.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //解析数据
        List<Long> ids = new ArrayList<>(idStr.size());//blogid
        long minTime = 0;//最小时间戳
        int os = 1;// 偏移量
        for(ZSetOperations.TypedTuple<String> tuple : idStr){
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if(time == minTime)
            {
                os++;
            }
            else {
                minTime = time;
                os = 1;
            }
        }
        //查询博客
        List<Blog> blogs = query().in("id", ids).
                last("ORDER BY FIELD(id, " + StrUtil.join(",", ids) + ")").list();
        if(blogs == null || blogs.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        for (Blog blog : blogs) {
            fillOtherBlogFiled(blog);
        }
        //封装数据
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }

    @Transactional
    public void cancelLiked(Long id, String likedKey) {
        this.update().setSql("liked = liked - 1").eq("id", id).update();
        stringRedisTemplate.opsForZSet().remove(likedKey, UserHolder.getUser().getId().toString());
    }
    @Transactional
    public void liked(Long id, String likedKey) {
        this.update().setSql("liked = liked + 1").eq("id", id).update();
        stringRedisTemplate.opsForZSet().add(likedKey, String.valueOf(UserHolder.getUser().getId()),System.currentTimeMillis());
    }
}
