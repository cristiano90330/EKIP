/* 处理注册、认证、访问令牌、刷新轮换与当前账号查询。 */

package com.pony.knowledge.service.impl;

import com.pony.knowledge.dto.LoginDTO;
import com.pony.knowledge.dto.RegisterDTO;
import com.pony.knowledge.dto.UserProfileDTO;
import com.pony.knowledge.dto.UserSearchResultDTO;
import com.pony.knowledge.entity.User;
import com.pony.knowledge.entity.UserStatus;
import com.pony.knowledge.exception.BusinessException;
import com.pony.knowledge.exception.UnauthorizedException;
import com.pony.knowledge.mapper.UserMapper;
import com.pony.knowledge.security.AuthenticatedUserDetails;
import com.pony.knowledge.security.RefreshTokenStore;
import com.pony.knowledge.service.AccountAdminService;
import com.pony.knowledge.service.UserService;
import com.pony.knowledge.utils.JwtUtil;
import com.pony.knowledge.vo.LoginVO;

import io.jsonwebtoken.JwtException;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 账户业务主线：注册保存密码哈希，登录签发令牌，刷新消费旧票据并轮换，登出吊销刷新会话。
 * 先从 UserController 进入对应方法，再按分支阅读 UserMapper、登录用户加载和 RefreshTokenStore。
 */
@Service
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    private final UserMapper userMapper;

    private final PasswordEncoder passwordEncoder;

    private final AuthenticationManager authenticationManager;

    private final JwtUtil jwtUtil;

    private final RefreshTokenStore refreshTokenStore;
    private final AccountAdminService accountAdmin;

    /**
     * 保存处理注册、认证、访问令牌、刷新轮换与当前账号查询所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param userMapper UserMapper，负责提供登录账号、启停版本、部门和管理列表的数据库操作
     * @param passwordEncoder 本次操作所用的 PasswordEncoder 数据
     * @param authenticationManager 本次操作所用的 AuthenticationManager 数据
     * @param jwtUtil 本次操作使用的 JwtUtil 组件或记录
     * @param refreshTokenStore 本次操作使用的 RefreshTokenStore 组件或记录
     * @param accountAdmin 本次操作使用的 AccountAdminService 组件或记录
     */
    public UserServiceImpl(UserMapper userMapper,
                           PasswordEncoder passwordEncoder,
                           AuthenticationManager authenticationManager,
                           JwtUtil jwtUtil,
                           RefreshTokenStore refreshTokenStore,
                           AccountAdminService accountAdmin) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.refreshTokenStore = refreshTokenStore;
        this.accountAdmin = accountAdmin;
    }

    /**
     * 规范化并检查注册资料，使用密码摘要保存新账号。
     * 上游为 UserController.register。
     * 下游为 UserMapper.selectByUsername、AccountAdminService.isReservedUsername。
     *
     * @param dto 携带当前操作参数的 RegisterDTO 数据
     */
    @Override
    public void register(RegisterDTO dto){

        User existUser =
                userMapper.selectByUsername(dto.getUsername());

        if (existUser != null || accountAdmin.isReservedUsername(dto.getUsername())) {
            throw new BusinessException("用户名已经存在");
        }

        User user = new User();

        user.setUsername(dto.getUsername());

        user.setEmail(dto.getEmail());

        String encodePassword = passwordEncoder.encode(dto.getPassword());
        user.setPassword(encodePassword);

        user.setStatus(UserStatus.NORMAL);

        userMapper.insert(user);
    }

    /**
     * 认证用户名密码，返回同一登录会话的访问与刷新令牌。
     * 上游为 UserController.login。
     * 下游为 JwtUtil.newSessionId。
     *
     * @param dto 携带当前操作参数的 LoginDTO 数据
     * @return 本次得到的 LoginVO 实例
     */
    @Override
    public LoginVO login(LoginDTO dto){

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(dto.getUsername(), dto.getPassword()));

        AuthenticatedUserDetails principal = (AuthenticatedUserDetails) authentication.getPrincipal();

        LoginVO vo = issueTokenPair(principal.getUserId(), JwtUtil.newSessionId(), principal.getSessionVersion());
        vo.setUsername(principal.getUsername());
        return vo;
    }

    /**
     * 校验刷新令牌、账号与会话版本，再轮换凭证。
     * 上游为 UserController.refresh。
     * 下游为 JwtUtil.parseRefreshToken、UserMapper.selectAccountState。
     *
     * @param refreshToken 刷新令牌
     * @return 本次得到的 LoginVO 实例
     */
    @Override
    public LoginVO refresh(String refreshToken) {

        JwtUtil.RefreshTokenInfo info;

        try {
            info = jwtUtil.parseRefreshToken(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("登录已过期，请重新登录");
        }

        User user = userMapper.selectAccountState(info.userId());
        if (user == null || !user.acceptsSession(info.sessionVersion())) {
            throw new UnauthorizedException("登录已过期，请重新登录");
        }

        if (refreshTokenStore.isSessionDead(info.sessionId())) {
            throw new UnauthorizedException("登录已过期，请重新登录");
        }

        RefreshTokenStore.Redeemed redeemed = refreshTokenStore.redeem(info.tokenId());

        if (redeemed.status() == RefreshTokenStore.RedeemStatus.ERROR) {
            throw new UnauthorizedException("登录已过期，请重新登录");
        }

        // 区分刷新记录不存在和存储读写失败。缺失记录按重复使用处理，撤销仍在运行的同一会话。
        if (redeemed.status() == RefreshTokenStore.RedeemStatus.MISSING) {
            log.warn("检测到刷新票据重放，已吊销该会话的全部令牌: userId={}, sid={}, jti={}",
                    info.userId(), info.sessionId(), info.tokenId());
            refreshTokenStore.killSession(info.sessionId());
            throw new UnauthorizedException("登录已过期，请重新登录");
        }

        if (!info.userId().equals(redeemed.userId())) {
            log.error("刷新票据与登记不一致，已吊销该会话: sid={}, jti={}", info.sessionId(), info.tokenId());
            refreshTokenStore.killSession(info.sessionId());
            throw new UnauthorizedException("登录已过期，请重新登录");
        }

        return issueTokenPair(info.userId(), info.sessionId(), info.sessionVersion());
    }

    /**
     * 撤销与请求凭证对应的刷新能力和会话。
     * 上游为 UserController.logout。
     * 下游为 JwtUtil.parseRefreshToken、RefreshTokenStore.revoke。
     *
     * @param refreshToken 刷新令牌
     */
    @Override
    public void logout(String refreshToken) {

        try {
            JwtUtil.RefreshTokenInfo info = jwtUtil.parseRefreshToken(refreshToken);
            refreshTokenStore.revoke(info.tokenId());
            refreshTokenStore.killSession(info.sessionId());
        } catch (JwtException | IllegalArgumentException ignored) {

        }
    }

    /**
     * 生成同一会话版本的访问、刷新令牌并登记刷新凭证。
     * 上游为 UserServiceImpl.login、UserServiceImpl.refresh。
     * 下游为 JwtUtil.createAccessToken、JwtUtil.createRefreshToken。
     *
     * @param userId 用户 ID
     * @param sessionId 会话标识
     * @param sessionVersion 账号会话版本
     * @return 本次得到的 LoginVO 实例
     */
    private LoginVO issueTokenPair(Long userId, String sessionId, long sessionVersion) {
        String accessToken = jwtUtil.createAccessToken(userId, sessionVersion);
        String refreshToken = jwtUtil.createRefreshToken(userId, sessionId, sessionVersion);

        JwtUtil.RefreshTokenInfo info = jwtUtil.parseRefreshToken(refreshToken);
        refreshTokenStore.save(info.tokenId(), userId, sessionId);

        LoginVO vo = new LoginVO();
        vo.setId(userId);
        vo.setToken(accessToken);
        vo.setRefreshToken(refreshToken);
        vo.setExpiresIn(jwtUtil.accessExpireSeconds());
        return vo;
    }

    /**
     * 根据持久化账号读取当前资料。
     * 上游为 UserController.profile。
     * 下游为 UserMapper.selectAccountState。
     *
     * @param userId 用户 ID
     * @return 本次得到的 UserProfileDTO 实例
     */
    @Override
    public UserProfileDTO profile(Long userId) {
        User user = userMapper.selectAccountState(userId);
        if (user == null || user.getStatus() != UserStatus.NORMAL) {
            throw new UnauthorizedException("未认证或登录已过期");
        }
        return new UserProfileDTO(user.getId(), user.getUsername(), user.getDepartment());
    }

    /**
     * 根据用户名查找可共享的有效账号。
     * 上游为 UserController.search。
     * 下游为 UserMapper.searchByUsernameLike。
     *
     * @param keyword 用于模糊匹配的关键词
     * @return 与过滤条件匹配的 List&lt;UserSearchResultDTO&gt; 数据集合
     */
    @Override
    public List<UserSearchResultDTO> search(String keyword){
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        return userMapper.searchByUsernameLike(keyword);
    }
}
