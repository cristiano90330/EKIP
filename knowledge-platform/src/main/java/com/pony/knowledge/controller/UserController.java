/* 提供注册、登录、令牌续期、登出和账号信息查询接口。 */

package com.pony.knowledge.controller;

import com.pony.knowledge.common.Result;
import com.pony.knowledge.dto.LoginDTO;
import com.pony.knowledge.dto.RefreshDTO;
import com.pony.knowledge.dto.RegisterDTO;
import com.pony.knowledge.dto.UserProfileDTO;
import com.pony.knowledge.dto.UserSearchResultDTO;
import com.pony.knowledge.security.LoginThrottle;
import com.pony.knowledge.service.UserService;
import com.pony.knowledge.utils.CurrentUserUtil;
import com.pony.knowledge.vo.LoginVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 账户请求入口：注册、登录、刷新、登出及成员添加时的用户查找。
 * 登录先检查限流，再交给 UserService 认证；认证失败记数后继续抛出，由异常处理器决定响应。
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    private final LoginThrottle loginThrottle;
    private final CurrentUserUtil currentUser;

    /**
     * 接收注册资料并创建新账号。
     * 上游为 HTTP POST /user/register。
     * 下游为 UserService.register、Result.success。
     *
     * @param dto 携带当前操作参数的 RegisterDTO 数据
     * @return 包含 String 数据的业务响应
     */
    @PostMapping("/register")
    public Result<String> register(
            @Valid @RequestBody RegisterDTO dto){

        userService.register(dto);

        return Result.success("注册成功");
    }

    /**
     * 接收登录凭据并按照失败频率限制发放会话。
     * 上游为 HTTP POST /user/login。
     * 下游为 LoginThrottle.assertAllowed、UserService.login。
     *
     * @param dto 携带当前操作参数的 LoginDTO 数据
     * @param request 进入 HTTP 过滤链的当前请求
     * @return 包含 LoginVO 数据的业务响应
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto, HttpServletRequest request){

        String ip = request.getRemoteAddr();

        loginThrottle.assertAllowed(ip, dto.getUsername());

        try {
            LoginVO vo = userService.login(dto);
            loginThrottle.recordSuccess(ip, dto.getUsername());
            return Result.success(vo);
        } catch (AuthenticationException e) {

            loginThrottle.recordFailure(ip, dto.getUsername());
            throw e;
        }
    }

    /**
     * 接收刷新令牌并尝试续换访问与刷新凭证。
     * 上游为 HTTP POST /user/refresh。
     * 下游为 Result.success、UserService.refresh。
     *
     * @param dto 携带当前操作参数的 RefreshDTO 数据
     * @return 包含 LoginVO 数据的业务响应
     */
    @PostMapping("/refresh")
    public Result<LoginVO> refresh(@Valid @RequestBody RefreshDTO dto){
        return Result.success(userService.refresh(dto.getRefreshToken()));
    }

    /**
     * 请求注销当前会话可用的凭证。
     * 上游为 HTTP POST /user/logout。
     * 下游为 UserService.logout、Result.success。
     *
     * @param dto 携带当前操作参数的 RefreshDTO 数据
     * @return 包含 String 数据的业务响应
     */
    @PostMapping("/logout")
    public Result<String> logout(@Valid @RequestBody RefreshDTO dto){
        userService.logout(dto.getRefreshToken());
        return Result.success("已登出");
    }

    /**
     * 读取当前登录账号的标识与部门。
     * 上游为 HTTP GET /user/me。
     * 下游为 Result.success、UserService.profile。
     *
     * @return 包含 UserProfileDTO 数据的业务响应
     */
    @GetMapping("/me")
    public Result<UserProfileDTO> profile() {
        return Result.success(userService.profile(currentUser.getUserId()));
    }

    /**
     * 按用户名片段检索可供知识库共享的用户。
     * 上游为 HTTP GET /user/search。
     * 下游为 Result.success、UserService.search。
     *
     * @param username 登录用户名
     * @return 包含 List&lt;UserSearchResultDTO&gt; 数据的业务响应
     */
    @GetMapping("/search")
    public Result<List<UserSearchResultDTO>> search(@RequestParam String username){
        return Result.success(userService.search(username));
    }
}
