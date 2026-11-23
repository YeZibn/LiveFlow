package com.liveflow.api.profile.controller;

import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.profile.service.UserProfileService;
import com.liveflow.core.profile.service.UserProfileService.InvalidProfileException;
import com.liveflow.core.profile.service.UserProfileService.Profile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 当前登录账号的资料接口。 */
@RestController
@RequestMapping("/api/v1/users/me/profile")
public class UserProfileController {

    private static final List<String> EDITABLE_FIELDS = List.of("nickname", "avatarKey", "bio");
    private final UserProfileService profiles;

    /** @param profiles 本人资料读写服务 */
    public UserProfileController(UserProfileService profiles) {
        this.profiles = profiles;
    }

    /**
     * 返回已保存的本人资料，首次查看时返回默认资料。
     *
     * @param jwt 已通过签名和当前会话校验的 JWT
     * @return 不可缓存的本人资料响应
     * @throws AuthFailure 认证身份对应的账号已不存在
     * @throws DataAccessException 资料库读取失败
     */
    @GetMapping
    public ResponseEntity<ProfileResponse> read(@AuthenticationPrincipal Jwt jwt) {
        return response(profiles.read(Long.parseLong(jwt.getSubject())));
    }

    /**
     * 将完整表单保存到当前账号。
     *
     * @param jwt 已通过签名和当前会话校验的 JWT
     * @param body 客户端提交的资料 JSON
     * @return 不可缓存的已提交资料
     * @throws InvalidProfileException 请求结构或字段规则无效
     * @throws AuthFailure 认证身份对应的账号已不存在
     * @throws DataAccessException 资料库读写失败
     * @throws TransactionException 资料事务无法完成
     */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProfileResponse> save(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode body) {
        UpdateProfileRequest input = parse(body);
        // 资料归属只从安全上下文取得，提交的三个字段仅用于更新资料内容。
        return response(profiles.save(Long.parseLong(jwt.getSubject()), input.nickname(), input.avatarKey(), input.bio()));
    }

    /**
     * 解析完整资料表单，拒绝未知字段并收集缺失或类型错误的已知字段。
     * @param body HTTP 请求中的 JSON 节点
     * @return 包含昵称、头像标识和简介的原始表单
     * @throws InvalidProfileException 请求结构或字段类型无效
     */
    private static UpdateProfileRequest parse(JsonNode body) {
        // 在此入口检查字段与 JSON 类型，避免其他接口受全局反序列化规则影响。
        if (!body.isObject() || !EDITABLE_FIELDS.containsAll(body.propertyNames())) {
            throw new InvalidProfileException(Map.of());
        }
        Map<String, String> errors = new LinkedHashMap<>();
        for (String field : EDITABLE_FIELDS) {
            if (!body.path(field).isString()) {
                errors.put(field, "请提供字符串值");
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidProfileException(errors);
        }
        return new UpdateProfileRequest(body.get("nickname").asString(), body.get("avatarKey").asString(),
                body.get("bio").asString());
    }

    /**
     * 生成本人资料的统一读取或保存响应，内部账号 ID 以字符串形式传输。
     * @param profile 已组装的本人资料或默认资料
     * @return 不可缓存的 JSON 响应
     */
    private static ResponseEntity<ProfileResponse> response(Profile profile) {
        var body = new ProfileResponse(Long.toString(profile.userId()), profile.username(), profile.nickname(),
                profile.displayName(), profile.avatarKey(), profile.bio());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private record UpdateProfileRequest(String nickname, String avatarKey, String bio) {}

    /** 本人资料的 HTTP 响应，账号 ID 使用十进制字符串。 */
    public record ProfileResponse(String userId, String username, String nickname, String displayName, String avatarKey,
            String bio) {}
}
