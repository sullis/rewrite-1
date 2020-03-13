package org.openrewrite.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RemoteAddCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;

public class GitCommit {
    public static void main(String[] args) throws GitAPIException, URISyntaxException, IOException {
        Git git = Git.open(new File("/Users/jon/Projects/github/jkschneider/sample-github"));

        RemoteAddCommand remoteAddCommand = git.remoteAdd();
        remoteAddCommand.setName("origin");
        remoteAddCommand.setUri(new URIish("https://github.com/jkschneider/sample-github.git"));
        remoteAddCommand.call();

        UsernamePasswordCredentialsProvider credentialsProvider = new UsernamePasswordCredentialsProvider(
                "jkschneider",
                "e685615f7fed851ec8ebb7170a20c74222750291"
        );

        git.add().addFilepattern("test.txt").call();

        git.commit()
                .setCommitter("Jon Schneider", "jkschneider@gmail.com")
                .setMessage("Automatically updated test.txt")
                .call();

        git.push()
                .setCredentialsProvider(credentialsProvider)
                .setThin(true)
                .setRemote("origin")
                .call();
    }
}
