package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;
import com.hmdp.service.IBlogCommentsService;
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
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService blogCommentsService;

    /**
     * 发表评论
     */
    @PostMapping
    public Result saveComments(@RequestBody BlogComments comments) {
        return blogCommentsService.saveComments(comments);
    }

    /**
     * 查询博客的评论列表
     */
    @GetMapping("/{id}")
    public Result queryCommentsByBlogId(@PathVariable("id") Long blogId) {
        return blogCommentsService.queryCommentsByBlogId(blogId);
    }
}
