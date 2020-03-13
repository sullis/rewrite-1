package org.openrewrite.git;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.netty.handler.codec.http.*;
import net.lightbody.bmp.mitm.manager.ImpersonatingMitmManager;
import org.littleshoot.proxy.*;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;

/**
 * Used to snoop traffic between the Git CLI and a HTTPs-hosted remote Git repository.
 */
public class GitProxy {
    public static void main(String[] args) throws InterruptedException {
        Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logger.setLevel(Level.WARN);

        DefaultHttpProxyServer.bootstrap()
                .withManInTheMiddle(ImpersonatingMitmManager.builder().build())
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    @Override
                    public int getMaximumRequestBufferSizeInBytes() {
                        return 1048576;
                    }

                    @Override
                    public HttpFilters filterRequest(HttpRequest originalRequest) {
                        return new HttpFiltersAdapter(originalRequest) {
                            @Override
                            public HttpResponse clientToProxyRequest(HttpObject httpObject) {
                                System.out.println(originalRequest.getMethod() + " " + originalRequest.getUri());
                                if (HttpMethod.POST.equals(originalRequest.getMethod()) && originalRequest.getUri().endsWith("git-receive-pack")) {
                                    String response = ((HttpContent) httpObject).content().toString(Charset.defaultCharset());
                                    System.out.println(response);
                                }
                                return null; // continue processing as normal
                            }

                            @Override
                            public HttpObject serverToProxyResponse(HttpObject httpObject) {
                                if (httpObject instanceof DefaultHttpResponse) {
                                    DefaultHttpResponse response = (DefaultHttpResponse) httpObject;
                                    System.out.println("  " + response.getStatus());
//                                    System.out.println("  " + ((HttpContent) httpObject).content().readableBytes() + " bytes");
                                }
                                return httpObject;
                            }
                        };
                    }
                })
                .start();

        for (; ; ) {
            Thread.sleep(1000);
        }
    }
}
