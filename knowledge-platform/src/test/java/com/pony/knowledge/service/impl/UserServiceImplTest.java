/* 验证用户注册、登录、令牌续期和注销的服务层规则。 */

package com.pony.knowledge.service.impl;

import com.pony.knowledge.dto.LoginDTO;
import com.pony.knowledge.dto.RegisterDTO;
import com.pony.knowledge.dto.UserSearchResultDTO;
import com.pony.knowledge.entity.User;
import com.pony.knowledge.entity.UserStatus;
import com.pony.knowledge.exception.BusinessException;
import com.pony.knowledge.exception.UnauthorizedException;
import com.pony.knowledge.mapper.UserMapper;
import com.pony.knowledge.security.AuthenticatedUserDetails;
import com.pony.knowledge.security.RefreshTokenStore;
import com.pony.knowledge.utils.JwtUtil;
import com.pony.knowledge.service.AccountAdminService;
import com.pony.knowledge.vo.LoginVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * UserServiceImpl 的两处本轮改动：
 * ① register 落库 email 与 status（此前收了带 @Email 校验的邮箱却从未 setEmail）；
 * ② login 的认证决策整体交给 AuthenticationManager，且异常原样冒泡不做翻译。
 *
 * 用真的 BCryptPasswordEncoder 而不是 mock：这样「密码是 BCrypt 密文不是明文」是一条真断言，
 * 而不是在断言 mock 的返回值。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    /** 刷新票据里的会话标识；login 路径由 JwtUtil.newSessionId() 现场生成，故那些桩用 anyString()。 */
    private static final String SESSION_ID = "sid-1";

    @Mock
    private UserMapper userMapper;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private AccountAdminService accountAdmin;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private UserServiceImpl userService;

    /** 准备本组用例共享的测试数据和隔离环境，通过 {@code userMapper.selectAccountState}、{@code lenient} 初始化依赖。 */
    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(userMapper, passwordEncoder, authenticationManager, jwtUtil, refreshTokenStore,
                accountAdmin);
        User active = new User();
        active.setStatus(UserStatus.NORMAL);
        lenient().when(userMapper.selectAccountState(7L)).thenReturn(active);
    }

    /**
     * 返回当前计算采用的 {@code dto}。
     *
     * @param username 本次调用传入的 {@code String}
     * @param password 本次调用传入的 {@code String}
     * @param email 本次调用传入的 {@code String}
     * @return 当前记录中的 {@code dto}
     */
    private static RegisterDTO registerDto(String username, String password, String email) {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        dto.setEmail(email);
        return dto;
    }

    /**
     * 返回当前计算采用的 {@code dto}。
     *
     * @param username 本次调用传入的 {@code String}
     * @param password 本次调用传入的 {@code String}
     * @return 当前记录中的 {@code dto}
     */
    private static LoginDTO loginDto(String username, String password) {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        return dto;
    }

    /** 通过 {@code userMapper.selectByUsername}、{@code userService.register} 驱动该场景，核对 {@code saved.getStatus()} 等于 {@code UserStatus.NORMAL}。 */
    @Test
    void register_persistsEmailAndNormalStatus() {
        when(userMapper.selectByUsername("alice")).thenReturn(null);

        userService.register(registerDto("alice", "secret123", "alice@example.com"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User saved = captor.getValue();

        // 改造前 user.email 恒为 NULL：RegisterDTO 收了带 @Email 校验的邮箱却从未 setEmail
        assertThat(saved.getEmail()).isEqualTo("alice@example.com");
        // 业务语义该 Java 管，不依赖 DDL 的 DEFAULT 1：insert 之后对象上立刻有值
        assertThat(saved.getStatus()).isEqualTo(UserStatus.NORMAL);
        assertThat(saved.getUsername()).isEqualTo("alice");
    }

    /** 通过 {@code userMapper.selectByUsername}、{@code userService.register} 驱动该场景，布尔状态符合断言。 */
    @Test
    void register_storesBCryptHashNeverThePlaintext() {
        when(userMapper.selectByUsername("alice")).thenReturn(null);

        userService.register(registerDto("alice", "secret123", "alice@example.com"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        String stored = captor.getValue().getPassword();

        assertThat(stored).isNotEqualTo("secret123");
        assertThat(stored).doesNotContain("secret123");
        assertThat(stored).startsWith("$2a$");
        // 真的是 BCrypt：能被同一个 encoder 验回去
        assertThat(passwordEncoder.matches("secret123", stored)).isTrue();
        assertThat(passwordEncoder.matches("wrong-password", stored)).isFalse();
    }

    /** 通过 {@code userMapper.selectByUsername}、{@code userService.register} 驱动该场景，抛出 {@code BusinessException}，跳过受保护的调用。 */
    @Test
    void register_duplicateUsernameThrowsBusinessException() {
        User existing = new User();
        existing.setId(3L);
        existing.setUsername("alice");
        when(userMapper.selectByUsername("alice")).thenReturn(existing);

        assertThatThrownBy(() -> userService.register(registerDto("alice", "secret123", "a@b.com")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户名已经存在");

        verify(userMapper, never()).insert(any(User.class));
    }

    /** 通过 {@code userMapper.selectByUsername}、{@code userService.register} 驱动该场景，核对 {@code captor.getValue().getCreateTime()} 为空值、{@code captor.getValue().getUpdateTime()} 为空值。 */
    @Test
    void register_leavesTimestampsToDatabaseDefaults() {
        when(userMapper.selectByUsername("alice")).thenReturn(null);

        userService.register(registerDto("alice", "secret123", "alice@example.com"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        // 项目既有约定：时间列交给 DDL 默认值；MyBatis-Plus 的 insertStrategy=NOT_NULL
        // 会把 null 字段排除在 INSERT 列清单外，不会把 create_time 显式写成 NULL
        assertThat(captor.getValue().getCreateTime()).isNull();
        assertThat(captor.getValue().getUpdateTime()).isNull();
    }

    /** 通过 {@code authenticationManager.authenticate}、{@code jwtUtil.createAccessToken} 驱动该场景，核对 {@code vo.getId()} 等于 {@code 7L}、{@code vo.getExpiresIn()} 等于 {@code 900L}。 */
    @Test
    void login_delegatesTheDecisionToAuthenticationManagerAndSignsBothTokens() {
        AuthenticatedUserDetails principal =
                new AuthenticatedUserDetails(7L, "alice", "$2a$10$whatever", true, 0);
        Authentication authenticated =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        when(authenticationManager.authenticate(any())).thenReturn(authenticated);
        when(jwtUtil.createAccessToken(7L, 0)).thenReturn("signed.access.jwt");
        when(jwtUtil.createRefreshToken(eq(7L), anyString(), eq(0L))).thenReturn("signed.refresh.jwt");
        when(jwtUtil.parseRefreshToken("signed.refresh.jwt"))
                .thenReturn(new JwtUtil.RefreshTokenInfo(7L, "refresh-jti", SESSION_ID, 0));
        when(jwtUtil.accessExpireSeconds()).thenReturn(900L);

        LoginVO vo = userService.login(loginDto("alice", "secret123"));

        // 传给 AuthenticationManager 的必须是用户名 + 原始密码，Service 不再自己 matches()
        ArgumentCaptor<Authentication> captor = ArgumentCaptor.forClass(Authentication.class);
        verify(authenticationManager).authenticate(captor.capture());
        UsernamePasswordAuthenticationToken submitted =
                (UsernamePasswordAuthenticationToken) captor.getValue();
        assertThat(submitted.getPrincipal()).isEqualTo("alice");
        assertThat(submitted.getCredentials()).isEqualTo("secret123");

        assertThat(vo.getId()).isEqualTo(7L);
        assertThat(vo.getUsername()).isEqualTo("alice");
        assertThat(vo.getToken()).isEqualTo("signed.access.jwt");
        // 3.1 P2-16：登录同时签发刷新票据，并把它的 jti 登记进 Redis（登出即删这条）
        assertThat(vo.getRefreshToken()).isEqualTo("signed.refresh.jwt");
        assertThat(vo.getExpiresIn()).isEqualTo(900L);
        // 登录开的族：签票用的 sid 与登记用的 sid 必须是同一个，否则刷新链一上来就断（设计 §3.1）。
        // sid 由 JwtUtil.newSessionId() 现场生成、不可预知，所以用 captor 抓而不是写死。
        ArgumentCaptor<String> issuedSession = ArgumentCaptor.forClass(String.class);
        verify(jwtUtil).createRefreshToken(eq(7L), issuedSession.capture(), eq(0L));
        verify(refreshTokenStore).save("refresh-jti", 7L, issuedSession.getValue());
        assertThat(issuedSession.getValue()).isNotBlank();
        // userId 由 AuthenticatedUserDetails 直接携带，签 token 不需要再回查一次库（P1-9 / S1）
        verify(userMapper, never()).selectByUsername(any());
    }

    /** 通过 {@code jwtUtil.parseRefreshToken}、{@code refreshTokenStore.isSessionDead} 驱动该场景，核对 {@code vo.getId()} 等于 {@code 7L}。 */
    @Test
    void refresh_rotatesTheTokenPairWithinTheSameSession() {
        when(jwtUtil.parseRefreshToken("old.refresh"))
                .thenReturn(new JwtUtil.RefreshTokenInfo(7L, "old-jti", SESSION_ID, 0));
        when(refreshTokenStore.isSessionDead(SESSION_ID)).thenReturn(false);
        when(refreshTokenStore.redeem("old-jti"))
                .thenReturn(RefreshTokenStore.Redeemed.consumed(7L, SESSION_ID));
        when(jwtUtil.createAccessToken(7L, 0)).thenReturn("new.access");
        when(jwtUtil.createRefreshToken(7L, SESSION_ID, 0)).thenReturn("new.refresh");
        when(jwtUtil.parseRefreshToken("new.refresh"))
                .thenReturn(new JwtUtil.RefreshTokenInfo(7L, "new-jti", SESSION_ID, 0));
        when(jwtUtil.accessExpireSeconds()).thenReturn(900L);

        LoginVO vo = userService.refresh("old.refresh");

        assertThat(vo.getId()).isEqualTo(7L);
        assertThat(vo.getToken()).isEqualTo("new.access");
        assertThat(vo.getRefreshToken()).isEqualTo("new.refresh");
        // 旧票被原子消费（轮换）、新票登记，且 sid 沿用不变——新票仍是同一家族的一员
        verify(refreshTokenStore).redeem("old-jti");
        verify(refreshTokenStore).save("new-jti", 7L, SESSION_ID);
        verify(refreshTokenStore, never()).killSession(any());
    }

    /** 复用已轮换的刷新凭证，核对当前会话被整体吊销。 */
    @Test
    void refresh_replayedTicketKillsTheWholeSession() {
        when(jwtUtil.parseRefreshToken("replay.refresh"))
                .thenReturn(new JwtUtil.RefreshTokenInfo(7L, "replay-jti", SESSION_ID, 0));
        when(refreshTokenStore.isSessionDead(SESSION_ID)).thenReturn(false);
        // 签名有效但活跃记录不在 = 这张票已被消费过（或已被登出吊销）→ 判重放
        when(refreshTokenStore.redeem("replay-jti")).thenReturn(RefreshTokenStore.Redeemed.missing());

        assertThatThrownBy(() -> userService.refresh("replay.refresh"))
                .isInstanceOf(UnauthorizedException.class);

        // 本轮的核心：不只拒这一张，而是清算整条链
        verify(refreshTokenStore).killSession(SESSION_ID);
    }

    /** 模拟刷新时 Redis 失败，检查刷新被拒且不会被当成重放而吊销会话。 */
    @Test
    void refresh_redisErrorRejectsWithoutKillingTheSession() {
        // 设计 §5.1 的护栏：Redis 抖动不是重放证据。少这条断言，一次抖动就能把用户全端踢下线。
        when(jwtUtil.parseRefreshToken("err.refresh"))
                .thenReturn(new JwtUtil.RefreshTokenInfo(7L, "err-jti", SESSION_ID, 0));
        when(refreshTokenStore.isSessionDead(SESSION_ID)).thenReturn(false);
        when(refreshTokenStore.redeem("err-jti")).thenReturn(RefreshTokenStore.Redeemed.error());

        assertThatThrownBy(() -> userService.refresh("err.refresh"))
                .isInstanceOf(UnauthorizedException.class);

        verify(refreshTokenStore, never()).killSession(any());
    }

    /** 通过 {@code jwtUtil.parseRefreshToken}、{@code refreshTokenStore.isSessionDead} 驱动该场景，抛出 {@code UnauthorizedException}，跳过受保护的调用。 */
    @Test
    void refresh_deadSessionIsRejectedWithoutConsumingTheTicket() {
        // 顺序契约：族已清算时连活跃记录都不该再消费（redeem 是破坏性的 getAndDelete）
        when(jwtUtil.parseRefreshToken("dead.refresh"))
                .thenReturn(new JwtUtil.RefreshTokenInfo(7L, "dead-jti", SESSION_ID, 0));
        when(refreshTokenStore.isSessionDead(SESSION_ID)).thenReturn(true);

        assertThatThrownBy(() -> userService.refresh("dead.refresh"))
                .isInstanceOf(UnauthorizedException.class);

        verify(refreshTokenStore, never()).redeem(any());
    }

    /** 通过 {@code jwtUtil.parseRefreshToken}、{@code userService.refresh} 驱动该场景，抛出 {@code UnauthorizedException}，跳过受保护的调用。 */
    @Test
    void refresh_forgedTicketIsUnauthorizedBeforeTouchingRedis() {
        // 签名/类型/过期解析失败 → 直接 401，不查 Redis
        when(jwtUtil.parseRefreshToken("bad")).thenThrow(new io.jsonwebtoken.JwtException("bad"));

        assertThatThrownBy(() -> userService.refresh("bad"))
                .isInstanceOf(UnauthorizedException.class);

        verifyNoInteractions(refreshTokenStore);
    }

    /** 通过 {@code jwtUtil.parseRefreshToken}、{@code userService.logout} 驱动该场景，核对输出和依赖调用。 */
    @Test
    void logout_revokesTheTicketAndKillsTheSession() {
        when(jwtUtil.parseRefreshToken("some.refresh"))
                .thenReturn(new JwtUtil.RefreshTokenInfo(7L, "some-jti", SESSION_ID, 0));

        userService.logout("some.refresh");

        // 登出 = 删活跃记录（redis-cli 里看得见）+ 清算会话（本会话此前轮换出的链一并失效）
        verify(refreshTokenStore).revoke("some-jti");
        verify(refreshTokenStore).killSession(SESSION_ID);
    }

    /** 通过 {@code jwtUtil.parseRefreshToken}、{@code userService.logout} 驱动该场景，跳过受保护的调用。 */
    @Test
    void logout_invalidTicketIsIdempotentNoOp() {
        when(jwtUtil.parseRefreshToken("bad")).thenThrow(new io.jsonwebtoken.JwtException("bad"));

        userService.logout("bad"); // 不抛异常

        verifyNoInteractions(refreshTokenStore);
    }

    /** 通过 {@code authenticationManager.authenticate}、{@code userService.login} 驱动该场景，抛出 {@code BadCredentialsException}、{@code BusinessException}，跳过受保护的调用。 */
    @Test
    void login_badCredentialsPropagatesWithoutTranslation() {
        // 设计 §4.3 S4 的硬约束：Service 不得捕获 AuthenticationException。
        // 捕获了就等于把 401 语义吃掉 —— Controller 记不到限流失败计数，
        // GlobalExceptionHandler 也接不到，统一文案与时序防护全部落空。
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> userService.login(loginDto("alice", "wrong")))
                .isInstanceOf(BadCredentialsException.class)
                .isNotInstanceOf(BusinessException.class);

        verifyNoInteractions(jwtUtil);
    }

    /** 通过 {@code authenticationManager.authenticate}、{@code userService.login} 驱动该场景，抛出 {@code DisabledException}，跳过受保护的调用。 */
    @Test
    void login_disabledAccountPropagatesDisabledException() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new DisabledException("User is disabled"));

        // DisabledException 是 AuthenticationException 的子类，但 GlobalExceptionHandler
        // 会按继承深度选中 403 那个 handler，所以这里必须原样冒泡
        assertThatThrownBy(() -> userService.login(loginDto("bob", "secret123")))
                .isInstanceOf(DisabledException.class);

        verifyNoInteractions(jwtUtil);
    }

    /** 通过 {@code userService.search}、{@code r.get} 驱动用例，核对 {@code r} 长度为 {@code 1}、{@code r.get(0).getId()} 等于 {@code 5L}。 */
    @Test
    void search_returnsIdAndUsername() {
        UserSearchResultDTO dto = new UserSearchResultDTO();
        dto.setId(5L); dto.setUsername("alice");
        when(userMapper.searchByUsernameLike("ali")).thenReturn(List.of(dto));

        List<UserSearchResultDTO> r = userService.search("ali");

        assertThat(r).hasSize(1);
        assertThat(r.get(0).getId()).isEqualTo(5L);
        assertThat(r.get(0).getUsername()).isEqualTo("alice");
    }

    /** 通过 {@code userService.search} 驱动用例，核对 {@code userService.search(null)} 为空。 */
    @Test
    void search_blankKeywordShortCircuitsWithoutTouchingTheDatabase() {
        assertThat(userService.search("  ")).isEmpty();
        assertThat(userService.search(null)).isEmpty();
        verifyNoInteractions(userMapper);
    }
}
