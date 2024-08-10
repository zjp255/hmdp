package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    private IFollowService followService;

    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followId, @PathVariable Boolean isFollow){
        return followService.follow(followId, isFollow);
    }

    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followId){
        return followService.isFollow(followId);
    }

    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") Long id)
    {
        return followService.followCommons(id);
    }
}
