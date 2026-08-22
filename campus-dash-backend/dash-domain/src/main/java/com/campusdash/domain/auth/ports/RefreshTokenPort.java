package com.campusdash.domain.auth.ports;

import java.util.Optional;

/**
 * 支持 refresh token 的认证端口。
 *
 * Session 模式不需要它；JWT 模式用它弥补 access token 不便续期和吊销的短板。
 */
public interface RefreshTokenPort extends AuthPort {

    TokenPair loginWithRefresh(long userId);

    Optional<TokenPair> refresh(String refreshToken);

    void revokeRefresh(String refreshToken);

    record TokenPair(String accessToken, String refreshToken) {}
}
