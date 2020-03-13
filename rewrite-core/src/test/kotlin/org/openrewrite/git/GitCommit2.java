package org.openrewrite.git;

import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

public class GitCommit2 {
    public static void main(String[] args) {
        UsernamePasswordCredentialsProvider credentialsProvider = new UsernamePasswordCredentialsProvider(
                "jkschneider",
                "e685615f7fed851ec8ebb7170a20c74222750291"
        );

        new PushCommitWithoutCloningRepository.Builder()
                .build()
                .push(credentialsProvider, "https://github.com/jkschneider/sample-github.git");
    }
}
