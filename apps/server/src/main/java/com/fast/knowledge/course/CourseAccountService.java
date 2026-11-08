package com.fast.knowledge.course;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fast.knowledge.common.*;
import com.fast.knowledge.mapper.*;
import com.fast.knowledge.model.entity.KbUser;
import com.fast.knowledge.security.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class CourseAccountService {
    private final UserMapper users;
    private final AccountGuardMapper guard;
    private final PasswordEncoder encoder;
    private final JwtUtil jwt;
    public CourseAccountService(UserMapper users, AccountGuardMapper guard, PasswordEncoder encoder, JwtUtil jwt) {
        this.users = users; this.guard = guard; this.encoder = encoder; this.jwt = jwt;
    }
    @Transactional
    public AccountUser register(AccountRequests.Register request) {
        AccountRequests.username(request.username());
        AccountRequests.password(request.password(), true);
        String displayName = AccountRequests.displayName(request.displayName(), request.username());
        KbUser user = newUser(request.username(), request.password(), displayName, "USER");
        try { users.insert(user); }
        catch (DuplicateKeyException e) { throw new ApiException(40901, "用户名已存在"); }
        return AccountUser.from(user);
    }
    public LoginResult login(AccountRequests.Login request) {
        AccountRequests.username(request.username());
        AccountRequests.password(request.password(), false);
        KbUser user = users.findByUsername(request.username());
        if (user == null || !Integer.valueOf(1).equals(user.getStatus()) || !allowedRole(user.getRole())
                || !"LOCAL".equals(user.getAuthSource()) || !encoder.matches(request.password(), user.getPassword())) {
            throw new ApiException(40102, "用户名或密码错误");
        }
        return new LoginResult(jwt.createToken(user.getId(), user.getUsername(), user.getRole(), user.getAuthVersion()),
                user.getId(), user.getUsername(), user.getDisplayName(), user.getRole());
    }
    public AccountUser me() { return AccountUser.from(currentUser()); }
    public PageResult<AccountUser> list(long page, long pageSize, String keyword, String status) {
        requireAdmin();
        PageResult.validate(page, pageSize);
        if (keyword != null && keyword.codePointCount(0, keyword.length()) > 100) throw ApiException.invalid();
        Integer flag = status == null ? null : AccountRequests.status(status);
        var query = Wrappers.<KbUser>lambdaQuery().eq(flag != null, KbUser::getStatus, flag);
        if (keyword != null && !keyword.isBlank()) {
            String term = keyword.strip();
            query.and(q -> q.like(KbUser::getUsername, term).or().like(KbUser::getDisplayName, term));
        }
        query.orderByDesc(KbUser::getCreatedAt).orderByDesc(KbUser::getId);
        var result = users.selectPage(new Page<>(page, pageSize), query);
        return new PageResult<>(result.getRecords().stream().map(AccountUser::from).toList(), result.getTotal(), page, pageSize);
    }
    @Transactional
    public AccountUser changeStatus(long id, String status) {
        if (id < 1 || id > 9007199254740991L) throw ApiException.invalid();
        int desired = AccountRequests.status(status);
        // One stable database lock also handles an empty admin set and concurrent bootstrap.
        // All account mutations take locks in this order to prevent cross-admin deadlocks.
        lockGuard();
        List<KbUser> administrators = guard.lockAdministrators();
        requireAdmin(); // Recheck caller after acquiring locks, not just before the transaction.
        KbUser target = guard.lockUser(id);
        if (target == null) throw ApiException.notFound();
        if (Integer.valueOf(desired).equals(target.getStatus())) return AccountUser.from(target);
        if (desired == 0 && "ADMIN".equals(target.getRole())
                && administrators.stream().filter(u -> Integer.valueOf(1).equals(u.getStatus())).count() <= 1) {
            throw new ApiException(40905, "不能停用最后一个可用管理员");
        }
        long version = target.getAuthVersion() + 1;
        users.update(null, Wrappers.<KbUser>lambdaUpdate().eq(KbUser::getId, id)
                .set(KbUser::getStatus, desired).set(KbUser::getAuthVersion, version)
                .set(KbUser::getUpdatedAt, LocalDateTime.now()));
        return AccountUser.from(users.selectById(id));
    }
    @Transactional
    public boolean initializeAdmin(String username, String password) {
        lockGuard();
        if (!guard.lockAdministrators().isEmpty()) return false; // Includes disabled administrators.
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) return false;
        try { AccountRequests.username(username); AccountRequests.password(password, true); }
        catch (ApiException e) { throw new IllegalStateException("管理员初始化参数不符合要求"); }
        if (users.findByUsername(username) != null) throw new IllegalStateException("管理员初始化用户名已被占用");
        users.insert(newUser(username, password, username, "ADMIN"));
        return true;
    }
    private void lockGuard() {
        if (!Integer.valueOf(1).equals(guard.lockGuard())) throw new IllegalStateException("账号管理锁未初始化");
    }
    private KbUser newUser(String username, String password, String displayName, String role) {
        KbUser user = new KbUser();
        user.setUsername(username); user.setPassword(encoder.encode(password)); user.setDisplayName(displayName);
        user.setRole(role); user.setStatus(1); user.setAuthSource("LOCAL"); user.setMustChangePassword(0); user.setAuthVersion(0L);
        return user;
    }
    private KbUser currentUser() {
        Long id = UserContext.currentUserId();
        KbUser user = id == null ? null : users.selectById(id);
        if (user == null) throw new ApiException(40101, "未登录或令牌无效");
        if (!Integer.valueOf(1).equals(user.getStatus())) throw new ApiException(40103, "账号已停用，请重新登录");
        return user;
    }
    private void requireAdmin() {
        if (!"ADMIN".equals(currentUser().getRole())) throw new ApiException(40301, "无权限访问");
    }
    public static boolean allowedRole(String role) { return "USER".equals(role) || "ADMIN".equals(role); }
    public record LoginResult(String token, Long userId, String username, String displayName, String role) {
        @Override public String toString() { return "LoginResult[token redacted]"; }
    }
}
