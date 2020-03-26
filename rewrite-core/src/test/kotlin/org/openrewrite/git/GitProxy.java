package org.openrewrite.git;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.*;
import net.lightbody.bmp.mitm.manager.ImpersonatingMitmManager;
import org.eclipse.jgit.internal.storage.file.PackFile;
import org.eclipse.jgit.internal.storage.file.PackIndex;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import static java.lang.Integer.MAX_VALUE;

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
                        return MAX_VALUE;
                    }

                    @Override
                    public int getMaximumResponseBufferSizeInBytes() {
                        return MAX_VALUE;
                    }

                    @Override
                    public HttpFilters filterRequest(HttpRequest originalRequest) {
                        return new HttpFiltersAdapter(originalRequest) {
                            @Override
                            public HttpResponse clientToProxyRequest(HttpObject httpObject) {
                                System.out.println(originalRequest.getMethod() + " " + originalRequest.getUri());
                                for (Map.Entry<String, String> header : originalRequest.headers()) {
                                    System.out.println(header.getKey() + ": " + header.getValue());
                                }

                                if (HttpMethod.POST.equals(originalRequest.getMethod()) &&
                                        (originalRequest.getUri().endsWith("git-receive-pack") /*|| originalRequest.getUri().endsWith("git-upload-pack")*/)) {
                                    File packFile = new File("push.pack");

                                    ByteBuf content = ((HttpContent) httpObject).content();

                                    try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                                        content.getBytes(0, os, content.readableBytes());
                                        byte[] bytes = os.toByteArray();

                                        String response = new String(bytes, StandardCharsets.UTF_8);
                                        System.out.println(response.substring(0, response.indexOf("PACK")));

                                        ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
                                        bis.skip(response.indexOf("PACK"));
                                        Files.write(packFile.toPath(), bis.readAllBytes(), StandardOpenOption.WRITE);
                                    } catch (IOException e) {
                                        throw new UncheckedIOException(e);
                                    }

                                    PackFile parsedPackFile = new PackFile(packFile, 0);
                                    for (PackIndex.MutableEntry entry : parsedPackFile) {
                                        System.out.println(entry);
                                    }

                                    throw new RuntimeException("stop the push");

//                                    String response = ((HttpContent) httpObject).content().toString(Charset.defaultCharset());
//                                    System.out.println(response);
                                }

                                System.out.println("\n\n");

                                return null; // continue processing as normal
                            }

                            @Override
                            public HttpObject proxyToClientResponse(HttpObject httpObject) {
                                if (httpObject instanceof HttpContent) {
                                    ByteBuf content = ((HttpContent) httpObject).content();
                                    try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                                        content.getBytes(0, os, content.readableBytes());
                                        byte[] bytes = os.toByteArray();

                                        String body = new String(bytes, StandardCharsets.UTF_8);
                                        System.out.println(body);
                                    } catch (IOException e) {
                                        throw new UncheckedIOException(e);
                                    }
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
