/* 在隔离持久化依赖上验证账号注册、登录、刷新轮换与会话吊销。 */

package com.pony.knowledge.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.entity.User;
import com.pony.knowledge.entity.UserStatus;
import com.pony.knowledge.mapper.UserMapper;
import com.pony.knowledge.utils.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录链路的端到端集成测试（设计 §6.1 倒数第二行）：真 PostgreSQL + 真 BCrypt + 真 AuthenticationManager
 * + 真 Redis 限流，走完整的 Security 过滤链与 GlobalExceptionHandler。
 *
 * 钉住四件改造前不成立的事：
 * ① 用户不存在与密码错误对外同一状态码同一文案（枚举通道的消息面关闭）；
 * ② 禁用用户 403（此前 user.status 在代码里没有任何读取点，功能根本不存在）；
 * ③ 连续失败 5 次后第 6 次 429 + Retry-After；
 * ④ 成功登录清零计数。
 *
 * 前置：本机基础设施容器就绪即可（ekip-postgres 宿主 5432 / ekip-redis 宿主 6379，均为标准端口，
 * 与 application.yml 的默认值一致，不需要再设 POSTGRES_PORT / REDIS_PORT）。
 * JWT_SECRET 由 pom.xml 的 surefire systemPropertyVariables 注入，不需要出现在命令行。
 */
@SpringBootTest(properties = "task.reliability.recovery-enabled=false")
@AutoConfigureMockMvc
@Transactional
class UserControllerLoginIntegrationTest {

    /** MockHttpServletRequest.getRemoteAddr() 的默认值，也是限流键的 ip 段。 */
    private static final String MOCK_IP = "127.0.0.1";

    private static final String RAW_PASSWORD = "correct-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private StringRedisTemplate redis;

    /** 限流计数落在 Redis，不随 @Transactional 回滚，必须手工清（见 @AfterEach）。 */
    private final Set<String> throttleKeysToClean = new LinkedHashSet<>();

    /** 刷新票据同样落 Redis（auth:refresh:{jti}），不随事务回滚，测试结束要清。 */
    private final Set<String> refreshKeysToClean = new LinkedHashSet<>();

    /** 会话死亡标记（auth:family:dead:{sid}）也落 Redis，同样要清。 */
    private final Set<String> deadSessionKeysToClean = new LinkedHashSet<>();

    /** 从隔离 Redis 清除本类登记的刷新令牌状态。 */
    @AfterEach
    void cleanRedisKeys() {
        throttleKeysToClean.forEach(redis::delete);
        throttleKeysToClean.clear();
        refreshKeysToClean.forEach(redis::delete);
        refreshKeysToClean.clear();
        deadSessionKeysToClean.forEach(redis::delete);
        deadSessionKeysToClean.clear();
    }

    /** 解码并记录测试令牌关联的 Redis 键，方便结束后清理。由 {@code loginData}、{@code refreshNewTicket} 调用。接收需要验证的刷新令牌。返回 {@code info}。 */
    private JwtUtil.RefreshTokenInfo trackRefreshToken(String refreshToken) {
        JwtUtil.RefreshTokenInfo info = jwtUtil.parseRefreshToken(refreshToken);
        refreshKeysToClean.add("auth:refresh:" + info.tokenId());
        deadSessionKeysToClean.add("auth:family:dead:" + info.sessionId());
        return info;
    }

    /**
     * 构造 {@code String} 供测试使用。
     * 由 {@code unknownUserAndWrongPasswordAreIndistinguishableFromOutside}、{@code disabledUserIsForbidden403} 调用。
     * @param prefix 生成唯一数据使用的前缀
     * 返回 {@code prefix + "-" + System.nanoTime()}。
     */
    private String uniqueUsername(String prefix) {
        // nanoTime 后缀：username 上有唯一键，且 Redis 计数键 15 分钟才过期，
        // 重跑测试时不能撞上上一轮遗留的行或计数
        return prefix + "-" + System.nanoTime();
    }

    /**
     * 向夹具写入记录，使用 {@code user.setUsername}、{@code user.setPassword}。
     * 由 {@code unknownUserAndWrongPasswordAreIndistinguishableFromOutside}、{@code disabledUserIsForbidden403} 调用。
     * @param username 测试用户名
     * @param status 拟使用的状态值
     * 返回 {@code user}。
     */
    private User insertUser(String username, UserStatus status) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(RAW_PASSWORD));
        user.setEmail(username + "@example.com");
        user.setStatus(status);
        userMapper.insert(user);
        return user;
    }

    /**
     * 构造 {@code MvcResult} 供测试使用，使用 {@code throttleKeysToClean.add}、{@code objectMapper.writeValueAsString}。
     * 由 {@code unknownUserAndWrongPasswordAreIndistinguishableFromOutside}、{@code successfulLoginClearsTheFailureCounter} 调用。
     * @param username 测试用户名
     * @param password 测试密码
     * 返回 {@code MvcResult} 结果。
     */
    private MvcResult login(String username, String password) throws Exception {
        // 每个用过的用户名都会产生一个 Redis 计数键（失败时），登记好以便清理
        throttleKeysToClean.add("login:fail:" + username + ":" + MOCK_IP);
        return mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andReturn();
    }

    /**
     * 构造 {@code Map<String, Object>} 供测试使用，使用 {@code objectMapper.writeValueAsString}、{@code objectMapper.readValue}。
     * 由 {@code replayingAConsumedRefreshTicketKillsTheWholeSession}、{@code logoutKillsTheSessionNotJustThePresentedTicket} 调用。
     * @param username 测试用户名
     * 返回 {@code data}。
     */
    private Map<String, Object> loginData(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", RAW_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) objectMapper
                .readValue(result.getResponse().getContentAsString(), Map.class).get("data");
        trackRefreshToken((String) data.get("refreshToken"));
        return data;
    }

    /**
     * 构造 {@code int} 供测试使用，使用 {@code objectMapper.writeValueAsString}。
     * 由 {@code replayingAConsumedRefreshTicketKillsTheWholeSession}、{@code logoutKillsTheSessionNotJustThePresentedTicket} 调用。
     * @param refreshToken 需要验证的刷新令牌
     * 返回 {@code int} 结果。
     */
    private int refreshStatus(String refreshToken) throws Exception {
        return mockMvc.perform(post("/user/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andReturn().getResponse().getStatus();
    }

    /**
     * 构造 {@code String} 供测试使用，使用 {@code objectMapper.writeValueAsString}、{@code objectMapper.readValue}。
     * 由 {@code replayingAConsumedRefreshTicketKillsTheWholeSession}、{@code logoutKillsTheSessionNotJustThePresentedTicket} 调用。
     * @param refreshToken 需要验证的刷新令牌
     * 返回 {@code next}。
     */
    private String refreshNewTicket(String refreshToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/user/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk())
                .andReturn();
        String next = (String) ((Map<?, ?>) objectMapper
                .readValue(result.getResponse().getContentAsString(), Map.class).get("data"))
                .get("refreshToken");
        trackRefreshToken(next);
        return next;
    }

    /** 通过 {@code wrongPassword.getResponse} 执行本例并核对输出或记录。 */
    @Test
    void unknownUserAndWrongPasswordAreIndistinguishableFromOutside() throws Exception {
        String username = uniqueUsername("it-enum");
        insertUser(username, UserStatus.NORMAL);
        String nobody = uniqueUsername("it-nobody");

        MvcResult wrongPassword = login(username, "wrong-password");
        MvcResult unknownUser = login(nobody, "wrong-password");

        // 改造前：422 +「密码错误」 vs 422 +「用户不存在」→ 消息通道直接告诉攻击者哪些用户名存在
        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknownUser.getResponse().getStatus()).isEqualTo(401);

        String wrongPasswordBody = wrongPassword.getResponse().getContentAsString();
        String unknownUserBody = unknownUser.getResponse().getContentAsString();
        assertThat(wrongPasswordBody).contains("用户名或密码错误");
        // 两条响应体逐字相同：状态码、code、message 全都不可区分
        assertThat(unknownUserBody).isEqualTo(wrongPasswordBody);
        assertThat(unknownUserBody).doesNotContain("用户不存在");
        // 这里**不能**断言 doesNotContain("密码错误")：统一文案「用户名或密码错误」本身就含这个子串，
        // 那样断言永远失败。「不可区分」的正确表达是上面那条 isEqualTo —— 两种失败逐字相同，
        // 而不是某个子串不出现。下面这条钉住 message 的精确值，防止文案被改成可区分的两句。
        assertThat(objectMapper.readTree(wrongPasswordBody).get("message").asText())
                .isEqualTo("用户名或密码错误");
    }

    /** 核对 HTTP 403，并确认{@code $.code} 为 {@code 403}。调用路径经过 {@code throttleKeysToClean.add}。 */
    @Test
    void disabledUserIsForbidden403() throws Exception {
        String username = uniqueUsername("it-disabled");
        insertUser(username, UserStatus.DISABLED);
        // 先登记再请求：DisabledException 也是 AuthenticationException，Controller 会记一次失败计数
        throttleKeysToClean.add("login:fail:" + username + ":" + MOCK_IP);

        // 密码是对的：403 只能来自账号状态检查（DefaultPreAuthenticationChecks 读 isEnabled()），
        // 而不是凭证校验失败
        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", RAW_PASSWORD))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("账号已被禁用，请联系管理员"));
    }

    /**
     * 核对 HTTP 200，并确认{@code $.code} 为 {@code 200}、{@code $.code} 为 {@code 200}。
     * 调用路径经过 {@code objectMapper.writeValueAsString}。
     */
    @Test
    void normalUserLogsInAndGetsATokenThatCarriesTheUserId() throws Exception {
        String username = uniqueUsername("it-ok");
        User user = insertUser(username, UserStatus.NORMAL);

        MvcResult result = mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", RAW_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(username))
                .andReturn();

        Map<?, ?> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        String token = (String) data.get("token");

        // id 从解析后的 map 上断言，不走 jsonPath：JSON 里的小数字会被 Jackson 解析成 Integer，
        // 与 Long 直接 equals 是 false，会假失败
        assertThat(((Number) data.get("id")).longValue()).isEqualTo(user.getId());
        assertThat(token).isNotBlank();
        // 登录签出的 access token 必须能被 JwtAuthenticationFilter 用的同一个 JwtUtil 解回同一个 userId，
        // 也就是 principal = 裸 Long 这条约定在登录侧没有被破坏（设计 §3.2）
        assertThat(jwtUtil.getUserId(token)).isEqualTo(user.getId());
        // 3.1 P2-16：登录同时返回刷新票据与访问有效期
        String refreshToken = (String) data.get("refreshToken");
        assertThat(refreshToken).isNotBlank();
        assertThat((Number) data.get("expiresIn")).isNotNull();
        trackRefreshToken(refreshToken);
        // data 里不能出现密码哈希（P1-9）
        assertThat(data).containsOnlyKeys("id", "username", "token", "refreshToken", "expiresIn");
    }

    /** 用有效刷新令牌换取一对新凭据，并验证旧票据不能再换取会话。 */
    @Test
    void refreshIssuesANewPairAndInvalidatesTheOldTicket() throws Exception {
        String username = uniqueUsername("it-refresh");
        insertUser(username, UserStatus.NORMAL);

        MvcResult login = mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", RAW_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> loginData = (Map<?, ?>) objectMapper.readValue(
                login.getResponse().getContentAsString(), Map.class).get("data");
        String oldRefresh = (String) loginData.get("refreshToken");
        String oldJti = trackRefreshToken(oldRefresh).tokenId();

        // 换取新票
        MvcResult refreshed = mockMvc.perform(post("/user/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", oldRefresh))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andReturn();
        Map<?, ?> newData = (Map<?, ?>) objectMapper.readValue(
                refreshed.getResponse().getContentAsString(), Map.class).get("data");
        trackRefreshToken((String) newData.get("refreshToken"));

        // 轮换：旧票已原子消费，重放旧票 → 401
        assertThat(redis.hasKey("auth:refresh:" + oldJti)).isFalse();
        mockMvc.perform(post("/user/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", oldRefresh))))
                .andExpect(status().isUnauthorized());
    }

    /** 核对 HTTP 200、HTTP 401。调用路径经过 {@code objectMapper.writeValueAsString}。 */
    @Test
    void logoutRevokesTheRefreshTicketSoItCanNoLongerBeExchanged() throws Exception {
        String username = uniqueUsername("it-logout");
        insertUser(username, UserStatus.NORMAL);

        MvcResult login = mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", RAW_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> loginData = (Map<?, ?>) objectMapper.readValue(
                login.getResponse().getContentAsString(), Map.class).get("data");
        String refresh = (String) loginData.get("refreshToken");
        String jti = trackRefreshToken(refresh).tokenId();
        assertThat(redis.hasKey("auth:refresh:" + jti)).isTrue();

        // 登出（真吊销）
        mockMvc.perform(post("/user/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isOk());
        assertThat(redis.hasKey("auth:refresh:" + jti)).isFalse();

        // 登出后再拿这张票刷新 → 401，「登出变成真的」
        mockMvc.perform(post("/user/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isUnauthorized());
    }

    /** 重放已消费的刷新令牌，确认同一会话中后续票据也失效。 */
    @Test
    void replayingAConsumedRefreshTicketKillsTheWholeSession() throws Exception {
        String username = uniqueUsername("it-replay");
        insertUser(username, UserStatus.NORMAL);
        String oldRefresh = (String) loginData(username).get("refreshToken");
        String newRefresh = refreshNewTicket(oldRefresh);   // 正常轮换一次，拿到同族的合法新票

        // 重放已被消费的旧票 → 401，并清算整个会话
        assertThat(refreshStatus(oldRefresh)).isEqualTo(401);

        // —— 本用例的核心：改造前到这里新票仍能换到票（窃取者不被暴露、真用户照常续期，
        // 这正是 02 章 §3.1 记的「基础防重放」的局限）；现在同族的合法新票也必须 401。
        assertThat(refreshStatus(newRefresh)).isEqualTo(401);
        assertThat(redis.hasKey("auth:family:dead:"
                + jwtUtil.parseRefreshToken(oldRefresh).sessionId())).isTrue();
    }

    /** 在刷新轮换过程中登出，确认该会话的有效刷新票据全部失效。 */
    @Test
    void logoutKillsTheSessionNotJustThePresentedTicket() throws Exception {
        String username = uniqueUsername("it-logout-cascade");
        insertUser(username, UserStatus.NORMAL);
        String loginRefresh = (String) loginData(username).get("refreshToken");
        // 轮换一次，让这条链上存在「登录票（已消费）+ 当前票（活跃）」两代
        String current = refreshNewTicket(loginRefresh);

        mockMvc.perform(post("/user/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", current))))
                .andExpect(status().isOk());

        // 登出这张票的活跃记录被删（logoutRevokes... 的口径不变），且族被清算
        assertThat(redis.hasKey("auth:refresh:"
                + jwtUtil.parseRefreshToken(current).tokenId())).isFalse();
        assertThat(redis.hasKey("auth:family:dead:"
                + jwtUtil.parseRefreshToken(current).sessionId())).isTrue();
        assertThat(refreshStatus(current)).isEqualTo(401);
    }

    /** 为同一账号建立两个会话，确认一个会话的状态变化与另一个隔离。 */
    @Test
    void separateLoginsAreSeparateSessions() throws Exception {
        // 「跨设备」要有反证：清算一个族不能顺手清掉同账号的另一个族。
        // 同账号登录两次 = 两个族、两条独立链（两次签发各生成一个 sid）。
        String username = uniqueUsername("it-two-sessions");
        insertUser(username, UserStatus.NORMAL);
        String a1 = (String) loginData(username).get("refreshToken");
        String b1 = (String) loginData(username).get("refreshToken");
        assertThat(a1).isNotEqualTo(b1);

        String a2 = refreshNewTicket(a1);
        assertThat(refreshStatus(a1)).isEqualTo(401);       // 重放 A1 → 灭 A 族
        assertThat(refreshStatus(a2)).isEqualTo(401);       // A 族当前票随之作废

        // B 族必须还活着：refreshNewTicket 内含 andExpect(isOk())，一旦被误灭就直接判失败
        refreshNewTicket(b1);
        assertThat(redis.hasKey("auth:family:dead:"
                + jwtUtil.parseRefreshToken(b1).sessionId())).isFalse();
    }

    /** 覆盖刷新的相关边界。通过 {@code uniqueUsername} 执行本例并核对输出或记录。 */
    @Test
    void refreshStillWorksAlongARotationChain() throws Exception {
        // 正常路径不被误伤：同一族连续轮换 3 次都要成功。
        // 这条是「严格灭族」的对照组——没有它，一次误判可能被当成偶发溜过去。
        String username = uniqueUsername("it-chain");
        insertUser(username, UserStatus.NORMAL);
        String ticket = (String) loginData(username).get("refreshToken");

        for (int round = 1; round <= 3; round++) {
            ticket = refreshNewTicket(ticket);
        }
    }

    /**
     * 核对 HTTP 401、HTTP 429，并确认{@code $.code} 为 {@code 429}、{@code $.code} 为 {@code 429}。
     * 调用路径经过 {@code objectMapper.writeValueAsString}。
     */
    @Test
    void sixthConsecutiveFailureIs429WithRetryAfter() throws Exception {
        String username = uniqueUsername("it-throttle");
        String body = objectMapper.writeValueAsString(
                Map.of("username", username, "password", "wrong-password"));
        throttleKeysToClean.add("login:fail:" + username + ":" + MOCK_IP);

        // max-attempts=5：前 5 次都进认证决策（401），第 6 次被 assertAllowed 挡在 BCrypt 之前
        for (int attempt = 1; attempt <= 5; attempt++) {
            mockMvc.perform(post("/user/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                // 429 只可能来自 Controller 第一行的 assertAllowed —— login() 还没被调用，
                // 这正是「锁定时不跑 BCrypt」的 DoS 缓解点
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "900"))
                .andExpect(jsonPath("$.message").value("登录失败次数过多，请 15 分钟后再试"));
    }

    /** 覆盖失败的相关边界。通过 {@code throttleKeysToClean.add} 执行本例并核对输出或记录。 */
    @Test
    void successfulLoginClearsTheFailureCounter() throws Exception {
        String username = uniqueUsername("it-clear");
        insertUser(username, UserStatus.NORMAL);
        String key = "login:fail:" + username + ":" + MOCK_IP;
        throttleKeysToClean.add(key);

        login(username, "wrong-password");
        assertThat(redis.hasKey(key)).isTrue();

        login(username, RAW_PASSWORD);

        // 成功登录必须清零，否则历史失败会把正常用户越推越接近锁定
        assertThat(redis.hasKey(key)).isFalse();
    }

    /** 通过注册入口建立账号，读取数据库记录核对邮箱及初始启用状态。 */
    @Test
    void registerPersistsEmailAndNormalStatus() throws Exception {
        String username = uniqueUsername("it-register");
        String email = username + "@example.com";
        throttleKeysToClean.add("login:fail:" + username + ":" + MOCK_IP);

        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", "secret123", "email", email))))
                .andExpect(status().isOk())
                // Result.success(T data) = new Result<>(200, "success", data)：
                // Controller 传的「注册成功」落在 data，message 恒为字面量 "success"
                .andExpect(jsonPath("$.data").value("注册成功"));

        User saved = userMapper.selectByUsername(username);
        assertThat(saved).isNotNull();
        // 改造前 RegisterDTO 收了带 @Email 校验的邮箱却从未 setEmail → user.email 恒为 NULL
        assertThat(saved.getEmail()).isEqualTo(email);
        assertThat(saved.getStatus()).isEqualTo(UserStatus.NORMAL);
        assertThat(saved.getCreateTime()).isNotNull();

        // 注册完必须能立刻登录（同一条链路上 BCrypt 密文可被框架验回）
        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", "secret123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username));
    }

    /** 核对 HTTP 422，并确认{@code $.code} 为 {@code 422}。调用路径经过 {@code objectMapper.writeValueAsString}。 */
    @Test
    void registerDuplicateUsernameIs422() throws Exception {
        String username = uniqueUsername("it-dup");
        insertUser(username, UserStatus.NORMAL);

        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", "secret123"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.message").value("用户名已经存在"));
    }
}
