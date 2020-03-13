package org.openrewrite.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import java.io.IOException;
import java.net.*;
import java.util.Collections;
import java.util.List;

public class LatestTree {
    public static final String BRANCH = "master";

    public static void main(String[] args) throws GitAPIException, IOException {
        ProxySelector.setDefault(new ProxySelector() {
            final ProxySelector delegate = ProxySelector.getDefault();

            @Override
            public List<Proxy> select(URI uri) {
                if (uri.toString().contains("github")
                        && uri.toString().contains("https")) {
                    return Collections.singletonList(new Proxy(Proxy.Type.HTTP, InetSocketAddress
                            .createUnresolved("localhost", 8080)));
                }
                return delegate == null ? Collections.singletonList(Proxy.NO_PROXY) : delegate.select(uri);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                if (uri == null || sa == null || ioe == null) {
                    throw new IllegalArgumentException("Arguments can't be null.");
                }
            }
        });

        UsernamePasswordCredentialsProvider credentialsProvider = new UsernamePasswordCredentialsProvider(
                "jkschneider",
                "e685615f7fed851ec8ebb7170a20c74222750291"
        );

        DfsRepositoryDescription repoDesc = new DfsRepositoryDescription();
        InMemoryRepository repo = new InMemoryRepository(repoDesc);

        StoredConfig config = repo.getConfig();
        config.setBoolean( "http", null, "sslVerify", false );
        config.save();

        Git git = new Git(repo);
        git.fetch()
                .setThin(true)
                .setCredentialsProvider(credentialsProvider)
//                .setRemote("https://github.com/jkschneider/sample-github.git")
                .setRemote("https://github.com/micrometer-metrics/micrometer.git")
                .setRefSpecs(new RefSpec("+refs/heads/*:refs/heads/*"))
                .call();
        repo.getObjectDatabase();

        ObjectId lastCommitId = repo.resolve("refs/heads/"+ BRANCH);
        RevWalk revWalk = new RevWalk(repo);
        RevCommit commit = revWalk.parseCommit(lastCommitId);
        RevTree tree = commit.getTree();

        TreeWalk treeWalk = new TreeWalk(repo);
        treeWalk.addTree(tree);
        treeWalk.setRecursive(true);
//        treeWalk.setFilter(PathFilter.create("test.txt"));
        treeWalk.setFilter(PathFilter.create("README.md"));

        if (!treeWalk.next()) {
            System.out.println("Not found");
            return;
        }

        ObjectId objectId = treeWalk.getObjectId(0);
        ObjectLoader loader = repo.open(objectId);
        loader.copyTo(System.out);
    }
}
