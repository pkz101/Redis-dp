package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.BlogComments;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

    @Resource
    private IUserService userService;

    @Resource
    private IBlogService blogService;

    @Override
    @Transactional
    public Result saveComments(BlogComments comments) {
        Long userId = UserHolder.getUser().getId();
        comments.setUserId(userId);
        comments.setCreateTime(LocalDateTime.now());
        comments.setUpdateTime(LocalDateTime.now());
        comments.setLiked(0);

        // 默认状态为正常
        if (comments.getStatus() == null) {
            comments.setStatus(false);
        }

        save(comments);

        // 更新博客评论数
        blogService.update()
                .setSql("comments = comments + 1")
                .eq("id", comments.getBlogId())
                .update();

        return Result.ok(comments.getId());
    }

    @Override
    public Result queryCommentsByBlogId(Long blogId) {
        List<BlogComments> comments = query()
                .eq("blog_id", blogId)
                .orderByAsc("create_time")
                .list();

        // 填充用户昵称和头像
        if (comments != null && !comments.isEmpty()) {
            for (BlogComments comment : comments) {
                User user = userService.getById(comment.getUserId());
                if (user != null) {
                    comment.setNickName(user.getNickName());
                    comment.setIcon(user.getIcon());
                }
            }
        }

        return Result.ok(comments);
    }
}
