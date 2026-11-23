package com.liveflow.core.profile.service;

import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.profile.mapper.UserProfileMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** 读取本人资料，并以事务为边界保存完整的资料表单。 */
@Service
public class UserProfileService {

    private static final String DEFAULT_AVATAR = "default";
    private static final Set<String> AVATARS = Set.of(DEFAULT_AVATAR, "blue", "green", "orange");
    private static final Pattern EDGE_WHITESPACE = Pattern.compile("(?U)^\\s+|\\s+$");

    private final UserProfileMapper profiles;
    private final TransactionTemplate transaction;

    /**
     * @param profiles 按账号读取和写入资料的 Mapper
     * @param transactionManager 资料库事务管理器
     */
    public UserProfileService(UserProfileMapper profiles, PlatformTransactionManager transactionManager) {
        this.profiles = profiles;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 读取本人资料。账号尚无资料行时计算默认值，无数据库写入。
     *
     * @param userId 已认证的账号 ID
     * @return 当前账号资料或默认资料
     * @throws AuthFailure 账号已不存在
     * @throws DataAccessException 资料读取失败
     */
    public Profile read(long userId) {
        var row = profiles.find(userId);
        if (row == null) {
            throw new AuthFailure(AuthFailure.Code.AUTH_SESSION_INACTIVE);
        }

        // 左连接保留已有账号，只有用户主动保存才创建资料行。
        if (row.nickname() == null) {
            return new Profile(row.userId(), row.username(), "", row.username(), DEFAULT_AVATAR, "");
        }
        String displayName = row.nickname().isEmpty() ? row.username() : row.nickname();
        return new Profile(row.userId(), row.username(), row.nickname(), displayName, row.avatarKey(), row.bio());
    }

    /**
     * 校验完整表单，并按账号保存资料。首次保存创建记录，之后覆盖三个编辑字段。
     *
     * @param userId 已认证的账号 ID
     * @param nickname 请求中的昵称
     * @param avatarKey 请求中的预设头像标识
     * @param bio 请求中的个人简介
     * @return 本次事务提交的规范化资料
     * @throws InvalidProfileException 任一字段不满足资料规则
     * @throws AuthFailure 账号已不存在
     * @throws DataAccessException 资料读写失败
     * @throws TransactionException 资料事务无法完成
     */
    public Profile save(long userId, String nickname, String avatarKey, String bio) {
        String normalizedName = EDGE_WHITESPACE.matcher(nickname).replaceAll("");
        String normalizedBio = EDGE_WHITESPACE.matcher(bio.replace("\r\n", "\n").replace('\r', '\n')).replaceAll("");
        validate(nickname, normalizedName, avatarKey, normalizedBio);

        return transaction.execute(status -> {
            // 主键为同账号的更新排序。提交前回读，保证响应使用本次写入的完整资料。
            profiles.upsert(userId, normalizedName, avatarKey, normalizedBio);
            return read(userId);
        });
    }

    private static void validate(String originalName, String nickname, String avatarKey, String bio) {
        Map<String, String> errors = new LinkedHashMap<>();
        int nicknameLength = nickname.codePointCount(0, nickname.length());
        if (originalName.codePoints().anyMatch(point -> Character.isISOControl(point)
                || point == 0x2028 || point == 0x2029 || isUnpairedSurrogate(point))
                || (nicknameLength != 0 && (nicknameLength < 2 || nicknameLength > 24))) {
            errors.put("nickname", "昵称应为 2–24 个字符且为单行，或留空使用登录用户名");
        }
        if (!AVATARS.contains(avatarKey)) {
            errors.put("avatarKey", "请选择一个预设头像");
        }
        if (bio.codePointCount(0, bio.length()) > 160 || bio.codePoints().anyMatch(UserProfileService::isUnpairedSurrogate)) {
            errors.put("bio", "简介应为有效文本，且最多 160 个字符");
        }
        if (!errors.isEmpty()) {
            throw new InvalidProfileException(errors);
        }
    }

    private static boolean isUnpairedSurrogate(int point) {
        return point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE;
    }

    /** 个人主页展示所需的本人资料。 */
    public record Profile(long userId, String username, String nickname, String displayName, String avatarKey,
            String bio) {}

    /** 资料字段校验失败，携带可直接展示的字段提示。 */
    public static final class InvalidProfileException extends RuntimeException {
        private final Map<String, String> fieldErrors;

        /** @param fieldErrors 失败字段及其公开错误提示 */
        public InvalidProfileException(Map<String, String> fieldErrors) {
            super("请检查请求字段");
            this.fieldErrors = Map.copyOf(fieldErrors);
        }

        /** @return 失败字段与提示的不可修改映射 */
        public Map<String, String> fieldErrors() {
            return fieldErrors;
        }
    }
}
