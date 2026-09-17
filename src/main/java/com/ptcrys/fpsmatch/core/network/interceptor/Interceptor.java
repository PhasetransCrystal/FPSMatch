package com.ptcrys.fpsmatch.core.network.interceptor;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public interface Interceptor {

    HttpRequest.Builder requestIntercept(HttpRequest.Builder builder, Object body);

    <T> HttpResponse<T> responseIntercept(HttpResponse<T> response);
}
