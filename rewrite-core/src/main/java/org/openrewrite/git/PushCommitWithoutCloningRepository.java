package org.openrewrite.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RemoteAddCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.internal.storage.dfs.*;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.RefList;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.List;

class PushCommitWithoutCloningRepository extends DfsRepository {
    public static class Builder extends DfsRepositoryBuilder<Builder, PushCommitWithoutCloningRepository> {
        @Override
        public PushCommitWithoutCloningRepository build() {
            return new PushCommitWithoutCloningRepository(this);
        }
    }

    private SyntheticObjDatabase objDatabase = new SyntheticObjDatabase();
    private SyntheticRefDatabase refDatabase;

    protected PushCommitWithoutCloningRepository(DfsRepositoryBuilder builder) {
        super(builder);
    }

    public void push(CredentialsProvider credentialsProvider, String remoteHttps) {
        try {
            Git git = new Git(this);

            RemoteAddCommand remoteAddCommand = git.remoteAdd();
            remoteAddCommand.setName("origin");
            remoteAddCommand.setUri(new URIish(remoteHttps));
            remoteAddCommand.call();

            Collection<Ref> refs = Git.lsRemoteRepository()
                    .setCredentialsProvider(credentialsProvider)
                    .setHeads(true)
                    .setRemote(remoteHttps)
                    .call();

            refDatabase = new SyntheticRefDatabase(refs.stream()
                    .filter(ref -> ref.getName().equals("refs/heads/master")).findAny()
                    .orElseThrow(() -> new IllegalStateException("No master branch in repository " + remoteHttps)));

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
        } catch (URISyntaxException | GitAPIException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public DfsObjDatabase getObjectDatabase() {
        return objDatabase;
    }

    @Override
    public RefDatabase getRefDatabase() {
        return refDatabase;
    }

    private class SyntheticRefDatabase extends DfsRefDatabase {
        private final RefCache refCache;

        protected SyntheticRefDatabase(Ref head) {
            super(PushCommitWithoutCloningRepository.this);
            RefList<Ref> sym = RefList.emptyList().put(new SymbolicRef("head", head));
            this.refCache = new RefCache(RefList.emptyList().put(head), sym);
        }

        @Override
        protected RefCache scanAllRefs() {
            return refCache;
        }

        @Override
        protected boolean compareAndPut(Ref oldRef, Ref newRef) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected boolean compareAndRemove(Ref oldRef) {
            throw new UnsupportedOperationException();
        }
    }

    private class SyntheticObjDatabase extends DfsObjDatabase {
        protected SyntheticObjDatabase() {
            super(PushCommitWithoutCloningRepository.this, new DfsReaderOptions());
        }

        @Override
        protected DfsPackDescription newPack(PackSource source) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected void commitPackImpl(Collection<DfsPackDescription> desc, Collection<DfsPackDescription> replaces) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected void rollbackPack(Collection<DfsPackDescription> desc) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected List<DfsPackDescription> listPacks() {
            throw new UnsupportedOperationException();
        }

        @Override
        protected ReadableChannel openFile(DfsPackDescription desc, PackExt ext) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected DfsOutputStream writeFile(DfsPackDescription desc, PackExt ext) {
            throw new UnsupportedOperationException();
        }
    }
}
