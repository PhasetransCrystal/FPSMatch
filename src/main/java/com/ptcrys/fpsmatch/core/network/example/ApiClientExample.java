package com.ptcrys.fpsmatch.core.network.example;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.ptcrys.fpsmatch.core.network.ApiResponse;
import com.ptcrys.fpsmatch.core.network.NetworkModule;
import com.ptcrys.fpsmatch.core.network.RequestMethod;
import com.ptcrys.fpsmatch.core.network.download.DownloadException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * API客户端使用示例
 */
public class ApiClientExample {

    private static final NetworkModule network = NetworkModule.initializeNetworkModule("http://127.0.0.1:8081");

    public static void main(String[] args) throws Exception {
        download();
    }

    public static void download() throws DownloadException {
        Path path = Paths.get("C:", "");
        NetworkModule download = NetworkModule.initializeNetworkModule("");
        download.newRequest()
                .downloadRequest()
                .saveTo(path)
                .callback(progress -> {
                    System.out.printf("\r" + progress.toString());
                }).download();

        download.shutdown();
    }

    // 示例：发送GET请求获取用户信息
    public static void getUserInfo(String userId) {
        try {
            // 同步请求
            ApiResponse<User> response = network.newRequest(User.CODEC)
                    .setRequestMethod(RequestMethod.GET)
                    .addPath("users/" + userId)
                    .addHeader("Authorization", "Bearer YOUR_TOKEN")
                    .addQueryParam("fields", "id,name,email")
                    .execute();

            if (response.isSuccessful()) {
                User user = response.getData();
                System.out.println("用户信息: " + user.getName());
            } else {
                System.err.println("请求失败: " + response.getError().getMessage());
            }
        } catch (Exception e) {
            e.fillInStackTrace();
        }
    }

    // 示例：发送POST请求创建新用户
    public static void createUserAsync() {
        User newUser = new User();
        newUser.setName("John Doe");
        newUser.setEmail("john@example.com");
        newUser.setAge(30);

        // 异步请求
        network.newRequest(User.CODEC)
                .setRequestMethod(RequestMethod.POST)
                .addPath("users")
                .addHeader("Authorization", "Bearer YOUR_TOKEN")
                .setJsonBody(newUser)
                .executeAsync()
                .thenAccept(response -> {
                    if (response.isSuccessful()) {
                        System.out.println("用户创建成功: " + response.getData().getId());
                    } else {
                        System.err.println("创建失败: " +
                                (response.getError() != null ?
                                        response.getError().getMessage() :
                                        "HTTP " + response.getCode()));
                    }
                })
                .exceptionally(e -> {
                    System.err.println("请求失败: " + e.getMessage());
                    return null;
                });
    }

    // 示例：发送表单请求
    public static LoginResult login() {
        Map<String, String> formData = new HashMap<>();
        formData.put("username", "test");
        formData.put("password", "password123");

        try {
            ApiResponse<LoginResult> response = network.newRequest(LoginResult.CODEC)
                    .setRequestMethod(RequestMethod.POST)
                    .addPath("api/login")
                    .setFormBody(formData)
                    .execute();

            if (response.isSuccessful()) {
                System.out.println("登录成功，Token: " + response.getData().getToken());
                return response.getData();
            } else {
                System.err.println("登录失败: " + response.getError().getMessage());
            }

            return null;
        } catch (Exception e) {
            System.err.println(e.getMessage());
            return null;
        }
    }

    // 示例：获取用户列表
    public static void getUserList() {
        try {
            ApiResponse<List<User>> response = network.newRequest(Codec.list(User.CODEC))
                    .setRequestMethod(RequestMethod.GET)
                    .addPath("users")
                    .addQueryParam("page", "1")
                    .addQueryParam("limit", "2")
                    .execute();

            if (response.isSuccessful()) {
                System.out.println("用户列表大小: " + response.getData().size());
            }
        } catch (Exception e) {
            e.fillInStackTrace();
        }
    }

    // 示例数据模型类
    public static class User {

        public static final Codec<User> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(User::getId),
                Codec.STRING.fieldOf("name").forGetter(User::getName),
                Codec.STRING.fieldOf("email").forGetter(User::getEmail),
                Codec.INT.fieldOf("age").forGetter(User::getAge)).apply(instance, (id, name, email, age) -> {
                    User user = new User();
                    user.setId(id);
                    user.setName(name);
                    user.setEmail(email);
                    user.setAge(age);
                    return user;
                }));

        private String id;
        private String name;
        private String email;
        private int age;

        // Getters and Setters
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }
    }

    // 示例登录结果类
    public static class LoginResult {

        public static final Codec<LoginResult> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("token").forGetter(LoginResult::getToken),
                User.CODEC.fieldOf("user").forGetter(LoginResult::getUser)).apply(instance, (token, user) -> {
                    LoginResult result = new LoginResult();
                    result.setToken(token);
                    result.setUser(user);
                    return result;
                }));

        private String token;
        private User user;

        // Getters and Setters
        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public User getUser() {
            return user;
        }

        public void setUser(User user) {
            this.user = user;
        }
    }
}
