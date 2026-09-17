package com.ptcrys.fpsmatch.core.network.api;

import com.mojang.datafixers.util.Function3;
import com.ptcrys.fpsmatch.core.data.music.OnlineMusic;
import com.ptcrys.fpsmatch.core.network.ApiResponse;
import com.ptcrys.fpsmatch.core.network.NetworkModule;
import com.ptcrys.fpsmatch.core.network.RequestBuilder;
import com.ptcrys.fpsmatch.core.network.RequestMethod;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class MvpMusicApi {

    private final NetworkModule network;
    private final Function3<String, String, NetworkModule, RequestBuilder<OnlineMusic>> requestBuilder;

    public MvpMusicApi(String url) {
        this.network = NetworkModule.initializeNetworkModule(url);
        this.requestBuilder = (uuid, playerName, module) -> {
            Map<String, String> formData = new HashMap<>();
            formData.put("uuid", uuid);
            formData.put("playerName", playerName);
            return module.newRequest(OnlineMusic.CODEC)
                    .setRequestMethod(RequestMethod.POST)
                    .setFormBody(formData)
                    .addHeader("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        };
    }

    public MvpMusicApi(String url, Function3<String, String, NetworkModule, RequestBuilder<OnlineMusic>> requestBuilder) {
        this.network = NetworkModule.initializeNetworkModule(url);
        this.requestBuilder = requestBuilder;
    }

    public MvpMusicApi(NetworkModule module, Function3<String, String, NetworkModule, RequestBuilder<OnlineMusic>> requestBuilder) {
        this.network = module;
        this.requestBuilder = requestBuilder;
    }

    public Optional<OnlineMusic> requestMusicInfo(String uuid, String playerName) {
        try {
            ApiResponse<OnlineMusic> response = requestBuilder.apply(uuid, playerName, network).execute();
            if (response.isSuccessful()) {
                return Optional.ofNullable(response.getData());
            } else {
                System.err.println("fail: " + response.getError().getMessage());
            }
        } catch (Exception e) {
            System.err.println("http error: " + e.getMessage());
        }
        return Optional.empty();
    }
}
