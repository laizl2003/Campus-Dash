package com.campusdash.application.usecase;

import com.campusdash.domain.auth.model.UserAccount;
import com.campusdash.domain.auth.ports.CaptchaPort;
import com.campusdash.domain.auth.ports.PasswordHasher;
import com.campusdash.domain.auth.ports.UserRepository;
import com.campusdash.shared.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 注册用例：校验顺序、唯一性、以及"密码绝不落明文" */
class RegisterUserUseCaseTest {

    private FakeUsers users;
    private FakeCaptcha captcha;
    private RegisterUserUseCase useCase;

    @BeforeEach
    void setUp() {
        users = new FakeUsers();
        captcha = new FakeCaptcha();
        useCase = new RegisterUserUseCase(users, new FakeHasher(), captcha,
                new FakeWallets(), new com.campusdash.shared.SnowflakeIdGenerator(1),
                new FakeCredits(), new com.campusdash.domain.credit.ports.CreditRankingPort() {
                    @Override public void update(long campusId, String userId, int score) { }
                    @Override public java.util.List<Entry> top(long campusId, int limit) { return java.util.List.of(); }
                });
    }

    @Test
    @DisplayName("注册成功：生成 UUID、角色 user、只存哈希、验证码被消耗")
    void registers_with_uuid_and_hashed_password() {
        captcha.issue("cap-1", "AB12");

        RegisterUserUseCase.Registered registered = useCase.register(
                new RegisterUserUseCase.Command("student_zhang", "13812345678", "abc12345", "abc12345", "cap-1", "ab12"));

        assertEquals(UserAccount.ROLE_USER, registered.role());
        assertNotNull(UUID.fromString(registered.userId()), "userId 必须是 UUID");
        UserAccount saved = users.byName.get("student_zhang");
        assertNotNull(saved);
        assertEquals("hash:abc12345", saved.passwordHash(), "只能存哈希");
        assertFalse(saved.passwordHash().contains("abc12345") && !saved.passwordHash().startsWith("hash:"),
                "哈希里不能出现明文");
        assertEquals(UserAccount.ROLE_USER, saved.role());
        assertEquals(60, saved.creditScore());
        assertTrue(saved.enabled());
        assertTrue(captcha.consumed.contains("cap-1"), "验证码必须一次性");
    }

    @Test
    @DisplayName("验证码错误直接拒绝，且不做任何查库")
    void rejects_wrong_captcha_before_touching_repository() {
        BizException e = assertThrows(BizException.class, () -> useCase.register(
                new RegisterUserUseCase.Command("student_zhang", "13812345678", "abc12345", "abc12345", "cap-x", "ZZZZ")));
        assertEquals("CAPTCHA_INVALID", e.code().name());
        assertEquals(0, users.existsCalls, "验证码失败不应查库，避免被用来探测用户名");
    }

    @Test
    @DisplayName("手机号、密码、一致性各自返回明确错误码")
    void rejects_invalid_fields() {
        assertCode("INVALID_PHONE", new RegisterUserUseCase.Command(
                "student_a", "12345", "abc12345", "abc12345", captcha.issue("c1", "1111"), "1111"));
        assertCode("INVALID_PASSWORD", new RegisterUserUseCase.Command(
                "student_a", "13800000001", "abcdefgh", "abcdefgh", captcha.issue("c2", "2222"), "2222"));
        assertCode("PASSWORD_MISMATCH", new RegisterUserUseCase.Command(
                "student_a", "13800000001", "abc12345", "abc12346", captcha.issue("c3", "3333"), "3333"));
        assertCode("INVALID_USERNAME", new RegisterUserUseCase.Command(
                "a", "13800000001", "abc12345", "abc12345", captcha.issue("c4", "4444"), "4444"));
    }

    @Test
    @DisplayName("用户名 / 手机号重复被拒绝")
    void rejects_duplicates() {
        users.byName.put("student_zhang", sample("u1", "student_zhang", "13800000009"));

        assertCode("USERNAME_TAKEN", new RegisterUserUseCase.Command(
                "student_zhang", "13800000002", "abc12345", "abc12345", captcha.issue("d1", "1234"), "1234"));

        users.byPhone.put("13800000003", sample("u2", "other", "13800000003"));
        assertCode("PHONE_TAKEN", new RegisterUserUseCase.Command(
                "student_new", "13800000003", "abc12345", "abc12345", captcha.issue("d2", "5678"), "5678"));
    }

    private void assertCode(String expected, RegisterUserUseCase.Command command) {
        BizException e = assertThrows(BizException.class, () -> useCase.register(command));
        assertEquals(expected, e.code().name());
    }

    private static UserAccount sample(String id, String username, String phone) {
        Instant now = Instant.now();
        return new UserAccount(id, username, phone, "hash:x", now, now, now,
                UserAccount.ROLE_USER, "", 1L, 60, true, null, "", 0, "");
    }

    private static final class FakeHasher implements PasswordHasher {
        @Override public String hash(String raw) { return "hash:" + raw; }
        @Override public boolean matches(String raw, String stored) { return ("hash:" + raw).equals(stored); }
    }

    private static final class FakeCaptcha implements CaptchaPort {
        private final Map<String, String> codes = new LinkedHashMap<>();
        private final List<String> consumed = new ArrayList<>();

        String issue(String id, String code) { codes.put(id, code); return id; }

        @Override
        public Captcha create() {
            return new Captcha("generated", "", 300);
        }

        @Override
        public boolean verify(String captchaId, String code) {
            String expected = codes.get(captchaId);
            if (expected == null || !expected.equalsIgnoreCase(code)) {
                return false;
            }
            codes.remove(captchaId);
            consumed.add(captchaId);
            return true;
        }
    }

    private static final class FakeWallets implements com.campusdash.domain.wallet.ports.WalletRepository {
        final java.util.List<com.campusdash.domain.wallet.model.WalletAccount> accounts = new java.util.ArrayList<>();

        @Override public java.util.Optional<com.campusdash.domain.wallet.model.WalletAccount> findByOwner(
                String ownerId, com.campusdash.domain.wallet.model.AccountType type) {
            return accounts.stream().filter(a -> a.ownerId().equals(ownerId) && a.type() == type).findFirst();
        }
        @Override public void insertAccount(com.campusdash.domain.wallet.model.WalletAccount account) { accounts.add(account); }
        @Override public void deleteAccount(long accountId) { accounts.removeIf(a -> a.id() == accountId); }
        @Override public java.util.Optional<com.campusdash.domain.wallet.model.WalletAccount> findById(long id) { return java.util.Optional.empty(); }
        @Override public int casDebit(long accountId, com.campusdash.shared.Money amount) { return 1; }
        @Override public int casCredit(long accountId, com.campusdash.shared.Money amount) { return 1; }
        @Override public void insertLedger(com.campusdash.domain.wallet.model.LedgerEntry entry) { }
        @Override public void insertEscrow(com.campusdash.domain.wallet.model.EscrowOrder order) { }
        @Override public boolean escrowExists(long campusId, long errandId) { return false; }
        @Override public java.util.Optional<com.campusdash.domain.wallet.model.EscrowOrder> findEscrowByErrandId(long campusId, long errandId) { return java.util.Optional.empty(); }
        @Override public int casEscrowStatus(long campusId, long errandId, com.campusdash.domain.wallet.model.EscrowOrder.EscrowStatus from, com.campusdash.domain.wallet.model.EscrowOrder.EscrowStatus to) { return 0; }
        @Override public boolean ledgerExists(String bizNo) { return false; }
        @Override public com.campusdash.domain.wallet.ports.WalletRepository.PlatformLedgerPage queryPlatformLedger(
                com.campusdash.domain.wallet.model.AccountType type, java.time.Instant from, int offset, int limit) {
            return new com.campusdash.domain.wallet.ports.WalletRepository.PlatformLedgerPage(
                    java.util.List.of(), 0, 0, 0);
        }
        @Override public long netPlatformIncomeCents() { return 0; }
    }

    private static final class FakeCredits implements com.campusdash.domain.credit.ports.CreditRepository {
        @Override public int scoreOf(String userId) { return 60; }
        @Override public java.util.Optional<com.campusdash.domain.credit.model.CreditScore> find(String userId) { return java.util.Optional.empty(); }
        @Override public boolean applyEvent(com.campusdash.domain.credit.model.CreditEvent event) { return true; }
        @Override public void ensureScore(String userId, int score) { }
        @Override public java.util.List<com.campusdash.domain.credit.model.CreditEvent> recentEvents(String userId, int days, int limit) { return java.util.List.of(); }
        @Override public int windowDelta(String userId, int windowDays) { return 0; }
        @Override public int calibrateScores(int windowDays, int limit) { return 0; }
    }

    private static final class FakeUsers implements UserRepository {
        private final Map<String, UserAccount> byName = new LinkedHashMap<>();
        private final Map<String, UserAccount> byPhone = new LinkedHashMap<>();
        private int existsCalls;

        @Override public Optional<UserAccount> findByUsername(String username) { return Optional.ofNullable(byName.get(username)); }
        @Override public Optional<UserAccount> findById(String userId) { return byName.values().stream().filter(u -> u.userId().equals(userId)).findFirst(); }
        @Override public Optional<UserAccount> findByPhone(String phone) { return Optional.ofNullable(byPhone.get(phone)); }
        @Override public boolean existsByUsername(String username) { existsCalls++; return byName.containsKey(username); }
        @Override public boolean existsByPhone(String phone) { existsCalls++; return byPhone.containsKey(phone); }
        @Override public void insert(UserAccount account) { byName.put(account.username(), account); byPhone.put(account.phone(), account); }
        @Override public void markLoginSuccess(String userId, Instant at, String ip) { }
        @Override public void markOnline(String userId) { }
        @Override public void markOffline(String userId) { }
        @Override public int sweepOffline(Instant cutoff) { return 0; }
        @Override public void updateStatus(String userId, boolean enabled) { }
        @Override public void markLoginFailure(String userId) { }
        @Override public void updatePassword(String userId, String hash, Instant at) { }
        @Override public boolean deleteById(String userId) { return byName.remove(byName.entrySet().stream().filter(e -> e.getValue().userId().equals(userId)).map(java.util.Map.Entry::getKey).findFirst().orElse(null)) != null; }
        @Override public java.util.List<UserAccount> findAll() { return new java.util.ArrayList<>(byName.values()); }
    }
}
