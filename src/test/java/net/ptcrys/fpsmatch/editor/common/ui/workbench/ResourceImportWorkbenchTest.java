package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.api.extensions.*;
import net.ptcrys.fpsmatch.editor.api.registry.*;
import net.ptcrys.fpsmatch.editor.core.editing.*;
import net.ptcrys.fpsmatch.editor.core.resources.*;
import net.ptcrys.fpsmatch.editor.core.session.SessionRejection;
import net.ptcrys.fpsmatch.editor.core.storage.*;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceImportWorkbenchTest {
    @TempDir Path temporary;
    static final EditorId OWNER=id("test:import"),IMPORTER=id("test:import_raw"),TYPE=id("test:asset");
    static final TextContent LABEL=new TextContent.Literal("Raw resource");

    @Test void resourceOnlyProjectPreservesPrivateInputHasOneHistoryAndActuallySavesOnDisk() throws Exception {
        var adapter=new Adapter();Path project=temporary.resolve("only-resources"),input=input();ResourceCatalog imported;
        try(var h=new Harness(adapter)) {
            h.model.create(project,"Resources");h.drain();assertTrue(h.model.documents().isEmpty());
            h.inspect(input);assertFalse(h.model.unsaved());assertTrue(h.model.resourceImportReady());
            Files.write(input,new byte[]{9,9,9}); // Preview owns the captured source, not this path.
            h.model.acceptResourceImport();h.drain();assertTrue(h.model.unsaved());assertEquals(1,adapter.closed.get());
            imported=h.model.resources().orElseThrow().content();assertEquals(1,imported.resources().size());
            assertThrows(SessionRejection.class,()->h.model.closeProject(false));
            h.model.undoResources();assertFalse(h.model.unsaved());assertFalse(h.model.resources().orElseThrow().canUndo());
            h.model.redoResources();assertEquals(imported,h.model.resources().orElseThrow().content());
            h.model.saveAll();h.drain();assertFalse(h.model.unsaved());
        }
        try(var h=new Harness(adapter)) {
            h.model.open(project);h.drain();assertFalse(h.model.protectedMode());assertFalse(h.model.unsaved());
            assertEquals(imported,h.model.resources().orElseThrow().content());
        }
        try(var store=LocalProjectStore.open(project)) {
            var bytes=new ByteArrayOutputStream();store.copyContent(imported.byteEntries().values().iterator().next().content(),bytes);
            assertArrayEquals(new byte[]{1,2,3},bytes.toByteArray());
        }
    }
    @Test void cancellationBeforeAndAfterWorkerAndDuringStageNeverAdoptsAndAlwaysCloses() throws Exception {
        for(int mode=0;mode<4;mode++) {
            var adapter=new Adapter();try(var h=new Harness(adapter)) {
                h.model.create(temporary.resolve("cancel-"+mode),"Cancel");h.drain();
                h.model.inspectResourceImport(IMPORTER,Map.of("source",input()));
                if(mode==0)h.model.cancel();
                else if(mode==1){h.io.next();h.model.cancel();}
                else {
                    h.drain();if(mode==2)adapter.onOpen=h.model::cancel;
                    h.model.acceptResourceImport();if(mode==3){h.io.next();h.model.cancel();}
                }
                h.drain();assertFalse(h.model.unsaved());assertTrue(h.model.resources().orElseThrow().content().resources().isEmpty());
                assertFalse(h.model.resourceImportReady());assertEquals(mode==0?0:1,adapter.closed.get());
                assertTrue(h.model.technicalFailure().isEmpty());
            }
        }
    }
    @Test void previewCancellationCloseAndRejectedExecutorAllReleaseOwnedPreparation() throws Exception {
        for(int mode=0;mode<5;mode++) {
            var adapter=new Adapter();try(var h=new Harness(adapter)) {
                h.model.create(temporary.resolve("release-"+mode),"Release");h.drain();h.inspect(input());
                if(mode==0)h.model.cancelResourceImport();
                else if(mode==1)h.model.closeProject(false);
                else {h.io.reject=true;if(mode==2)h.model.acceptResourceImport();else if(mode==3)h.model.cancelResourceImport();else h.model.closeProject(false);h.io.reject=false;}
                h.drain();assertEquals(1,adapter.closed.get());assertFalse(h.model.resourceImportReady());assertFalse(h.model.unsaved());
                if(mode>=2)assertInstanceOf(RejectedExecutionException.class,h.model.technicalFailure().orElseThrow());
            }
        }
    }
    @Test void cancellationOnRealIoWorkerReleasesSnapshotAndReturnsToOwnerWithoutAResourceEdit() throws Exception {
        var adapter=new Adapter();try(var h=new Harness(adapter)) {
            h.model.create(temporary.resolve("worker"),"Worker");h.drain();h.inspect(input());
            var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);
            adapter.onOpen=()->{entered.countDown();try{assertTrue(resume.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}};
            h.model.acceptResourceImport();Runnable work=h.io.tasks.removeFirst();Thread worker=new Thread(work,"resource-import-test-io");worker.start();
            try{assertTrue(entered.await(10,TimeUnit.SECONDS));h.model.cancel();}finally{resume.countDown();worker.join(10000);}
            assertFalse(worker.isAlive());h.drain();assertFalse(h.model.unsaved());assertTrue(h.model.technicalFailure().isEmpty());
            assertTrue(h.model.resources().orElseThrow().content().resources().isEmpty());assertEquals(1,adapter.closed.get());
        }
    }
    @Test void mutableSourceCannotChangeTheReferenceThatWasConfirmed() throws Exception {
        var adapter=new Adapter();try(var h=new Harness(adapter)) {
            h.model.create(temporary.resolve("mutable"),"Mutable");h.drain();h.inspect(input());
            adapter.output=new byte[]{7,8,9}; // Both content() and open() drift to another valid BlobRef.
            h.model.acceptResourceImport();h.drain();
            assertInstanceOf(IOException.class,h.model.technicalFailure().orElseThrow());
            assertFalse(h.model.unsaved());assertTrue(h.model.resources().orElseThrow().content().resources().isEmpty());assertEquals(1,adapter.closed.get());
        }
    }
    @Test void malformedAndReplacingCandidatesFailBeforeStagingAndReleaseTheirPreparation() throws Exception {
        for(int mode=1;mode<=3;mode++) {
            var adapter=new Adapter();try(var h=new Harness(adapter)) {
                h.model.create(temporary.resolve("invalid-"+mode),"Invalid");h.drain();h.inspect(input());
                h.model.acceptResourceImport();h.drain();ResourceCatalog before=h.model.resources().orElseThrow().content();
                adapter.mode=mode;h.model.inspectResourceImport(IMPORTER,Map.of("source",input()));h.drain();
                assertTrue(h.model.technicalFailure().isPresent());assertFalse(h.model.resourceImportReady());
                assertEquals(before,h.model.resources().orElseThrow().content());assertEquals(2,adapter.closed.get());
            }
        }
    }
    @Test void stalePreviewRejectsRatherThanRebasingAndSaveStillUsesFullContentValidation() throws Exception {
        var adapter=new Adapter();try(var h=new Harness(adapter)) {
            h.model.create(temporary.resolve("stale"),"Stale");h.drain();h.inspect(input());h.model.acceptResourceImport();h.drain();
            h.inspect(input());h.model.undoResources();assertThrows(SessionRejection.class,h.model::acceptResourceImport);
            assertTrue(h.model.resources().orElseThrow().content().resources().isEmpty());h.model.cancelResourceImport();h.drain();
            h.model.redoResources();adapter.rejectValidation=true;h.model.saveAll();h.drain();
            assertTrue(h.model.unsaved());assertTrue(h.model.technicalFailure().isPresent());
            adapter.rejectValidation=false;h.model.saveAll();h.drain();assertFalse(h.model.unsaved());
        }
    }
    @Test void missingExtraRelativeAndOversizeInputsDoNotReachAdapter() throws Exception {
        var adapter=new Adapter();try(var h=new Harness(adapter)) {
            h.model.create(temporary.resolve("inputs"),"Inputs");h.drain();
            assertThrows(SessionRejection.class,()->h.model.inspectResourceImport(IMPORTER,Map.of()));
            assertThrows(SessionRejection.class,()->h.model.inspectResourceImport(IMPORTER,Map.of("source",input(),"extra",input())));
            h.model.inspectResourceImport(IMPORTER,Map.of("source",Path.of("relative")));h.drain();
            assertInputFailure(h.model,"absolute_required");
            h.model.inspectResourceImport(IMPORTER,Map.of("source",temporary.resolve("private-missing-source")));h.drain();
            assertInputFailure(h.model,"missing");
            h.model.inspectResourceImport(IMPORTER,Map.of("source",temporary));h.drain();
            assertInputFailure(h.model,"not_regular");
            Path large=temporary.resolve("large");Files.write(large,new byte[5]);
            h.model.inspectResourceImport(IMPORTER,Map.of("source",large));h.drain();
            assertInputFailure(h.model,"limit");assertEquals(0,adapter.calls);assertFalse(h.model.unsaved());
            assertTrue(h.model.resources().orElseThrow().content().resources().isEmpty());
            h.model.inspectResourceImport(IMPORTER,Map.of("source",input()));
            assertTrue(h.model.failureDiagnostic().isEmpty());h.model.cancel();h.drain();
            assertTrue(h.model.failureDiagnostic().isEmpty());assertFalse(h.model.resourceImportReady());
        }
    }
    @Test void synchronousResourceFailuresHaveTheirOwnScopeAndNeverOrphanWorkers() throws Exception {
        var adapter=new Adapter();try(var h=new Harness(adapter)) {
            h.model.create(temporary.resolve("source-scope"),"Inputs");h.drain();
            h.model.report(new IllegalArgumentException("private field raw"));
            var missing=assertThrows(SessionRejection.class,()->h.model.inspectResourceImport(IMPORTER,Map.of()));
            h.model.reportResourceImportFailure(missing);
            assertEquals("resource_inspecting",h.model.failureDiagnostic().orElseThrow().operation());
            assertEquals(ProjectWorkbench.text("resource_failure_required"),h.model.failureDiagnostic().orElseThrow().reason());
            var badPath=new InvalidPathException("private raw path","private reason");
            h.model.reportResourceImportFailure(badPath);
            assertEquals(ProjectWorkbench.text("resource_failure_path"),h.model.failureDiagnostic().orElseThrow().reason());
            assertFalse(h.model.failureDiagnostic().orElseThrow().toString().contains("private"));
            h.model.inspectResourceImport(IMPORTER,Map.of("source",input()));
            h.model.reportResourceImportFailure(badPath);assertTrue(h.model.busy());
            h.drain();assertTrue(h.model.resourceImportReady());assertTrue(h.model.failureDiagnostic().isEmpty());
            h.model.cancelResourceImport();h.drain();assertFalse(h.model.unsaved());assertEquals(1,adapter.closed.get());
        }
    }
    @Test void resourceCancellationDismissesOwnedFailuresAndPreservesOtherTasks() throws Exception {
        for(boolean queued:List.of(false,true))try(var h=new Harness(new Adapter())) {
            h.model.create(temporary.resolve("cancel-failure-"+queued),"Resources");h.drain();
            h.model.inspectResourceImport(IMPORTER,Map.of("source",temporary.resolve("missing")));h.io.next();
            if(!queued){h.completion.next();assertTrue(h.model.failureDiagnostic().isPresent());}
            h.model.cancelResourceImport();h.drain();
            assertEquals(ProjectWorkbench.text("cancelled"),h.model.status());
            assertTrue(h.model.problems().isEmpty());assertTrue(h.model.failureDiagnostic().isEmpty());
            assertTrue(h.model.technicalFailure().isEmpty());assertFalse(h.model.resourceImportReady());assertFalse(h.model.unsaved());
            var unrelated=new IllegalArgumentException("new field failure");h.model.report(unrelated);
            var diagnostic=h.model.failureDiagnostic().orElseThrow();h.model.cancelResourceImport();
            assertEquals(diagnostic,h.model.failureDiagnostic().orElseThrow());assertSame(unrelated,h.model.technicalFailure().orElseThrow());
            h.inspect(input());h.model.report(unrelated);diagnostic=h.model.failureDiagnostic().orElseThrow();
            h.model.cancelResourceImport();h.drain();
            assertEquals(diagnostic,h.model.failureDiagnostic().orElseThrow());assertSame(unrelated,h.model.technicalFailure().orElseThrow());
            h.model.inspectResourceImport(IMPORTER,Map.of("source",input()));
            h.model.cancelImport();h.drain();assertTrue(h.model.resourceImportReady(),"another flow cannot cancel this worker");
            h.model.cancelResourceImport();h.drain();
        }
    }

    @Test void cancelledStagingCallbackPreservesAnUnrelatedFailureWithoutAdoptingResources() throws Exception {
        var adapter=new Adapter();try(var h=new Harness(adapter)) {
            h.model.create(temporary.resolve("late-staging"),"Resources");h.drain();h.inspect(input());
            h.model.acceptResourceImport();h.io.next();h.model.cancelResourceImport();
            var unrelated=new IllegalArgumentException("new field failure");h.model.report(unrelated);
            var diagnostic=h.model.failureDiagnostic().orElseThrow();var problems=h.model.problems();h.drain();
            assertEquals(diagnostic,h.model.failureDiagnostic().orElseThrow());assertSame(unrelated,h.model.technicalFailure().orElseThrow());
            assertEquals(problems,h.model.problems());assertTrue(h.model.resources().orElseThrow().content().resources().isEmpty());
            assertFalse(h.model.unsaved());assertFalse(h.model.resourceImportReady());assertEquals(1,adapter.closed.get());
        }
    }

    @Test void cancelledResourceInspectionStillReportsCleanupFailures() throws Exception {
        for(boolean ready:List.of(false,true)) {
            var adapter=new Adapter();try(var h=new Harness(adapter)) {
                h.model.create(temporary.resolve("cleanup-failure-"+ready),"Cleanup");h.drain();
                h.model.inspectResourceImport(IMPORTER,Map.of("source",input()));h.io.next();
                if(ready)h.completion.next();
                adapter.closeFailure=new IllegalStateException("cleanup failed");
                h.model.cancelResourceImport();h.drain();
                assertSame(adapter.closeFailure,h.model.technicalFailure().orElseThrow());
                assertFalse(h.model.resourceImportReady());assertFalse(h.model.unsaved());assertEquals(1,adapter.closed.get());
            }
        }
    }

    private static void assertInputFailure(ProjectWorkbench model,String key) {
        assertInstanceOf(ResourceImportCapture.InputException.class,model.technicalFailure().orElseThrow());
        var diagnostic=model.failureDiagnostic().orElseThrow();assertEquals("resource_inspecting",diagnostic.operation());
        assertEquals(new TextContent.Message("editor.workbench.resource_failure_"+key,List.of(new TextContent.NestedArgument(LABEL))),diagnostic.reason());
        assertEquals(diagnostic.reason(),model.problems().get(0).message());
        assertFalse(diagnostic.toString().contains("private-missing-source"));assertFalse(model.resourceImportReady());
    }
    @Test void typedImportFailureExposesTheOriginalUntranslatedDiagnostic()throws Exception {
        var adapter=new Adapter();try(var h=new Harness(adapter)) {
            h.model.create(temporary.resolve("diagnostic"),"Diagnostic");h.drain();adapter.mode=4;
            h.model.inspectResourceImport(IMPORTER,Map.of("source",input()));h.drain();
            var failure=assertInstanceOf(ResourceImportException.class,h.model.technicalFailure().orElseThrow());
            assertEquals(failure.problems(),h.model.problems());assertEquals(LABEL,h.model.problems().get(0).message());
            assertFalse(h.model.resourceImportReady());assertFalse(h.model.unsaved());
        }
    }

    private Path input()throws IOException {Path path=Files.createTempFile(temporary,"input-",".bin");Files.write(path,new byte[]{1,2,3});return path;}
    private static final class Adapter implements ResourceImporter {
        AtomicInteger closed=new AtomicInteger();int calls,mode;byte[] output;Runnable onOpen=()->{};boolean rejectValidation;RuntimeException closeFailure;
        public TextContent label(){return LABEL;}public List<Input> inputs(){return List.of(new Input("source",LABEL,4));}
        public Prepared inspect(ExtensionRegistry registry,Map<String,Source> inputs,ResourceCatalog baseline,UUID seed,java.util.function.BooleanSupplier cancel)throws IOException {
            if(mode==4)throw new ResourceImportException(List.of(new Problem(Problem.Severity.ERROR,id("test:source_rejected"),"source",LABEL)));
            calls++;try(var input=inputs.get("source").open()){output=input.readAllBytes();}
            UUID resource=UUID.nameUUIDFromBytes((seed+":r").getBytes()),version=UUID.nameUUIDFromBytes((seed+":v").getBytes()),source=UUID.nameUUIDFromBytes((seed+":b").getBytes());
            BlobRef ref=blob(output);var resources=new HashMap<>(baseline.resources());var versions=new HashMap<>(baseline.versions());var entries=new HashMap<>(baseline.byteEntries());
            resources.put(resource,new LogicalResource(resource,TYPE,"Raw",version));
            versions.put(version,new ResourceVersion(version,resource,IMPORTER,"1",Map.of(),source,Set.of(),Set.of()));
            entries.put(source,new ProjectState.Entry(source,OWNER.toString(),TYPE.toString(),IMPORTER.toString(),1,"Raw",ref,Set.of(),Set.of()));
            if(mode==1){var old=baseline.resources().values().iterator().next();resources.put(old.id(),new LogicalResource(old.id(),old.type(),"replacement",old.currentVersion()));}
            var candidate=new ResourceCatalog(resources,versions,entries);
            var sources=new HashMap<UUID,Source>();sources.put(source,new Source(){
                public BlobRef content(){return blob(output);}public InputStream open(){onOpen.run();return new ByteArrayInputStream(output);}
            });
            if(mode==2)sources.clear();if(mode==3)sources.put(UUID.randomUUID(),sources.get(source));
            return new Prepared(){
                public ResourceCatalog candidate(){return candidate;}public Map<UUID,Source> sources(){return sources;}
                public List<TextContent> summary(){return List.of(LABEL);}public void close(){closed.incrementAndGet();if(closeFailure!=null)throw closeFailure;}
            };
        }
    }
    private static ExtensionRegistry registry(Adapter adapter) {
        var api=new ApiVersion(1,0);var b=new ExtensionRegistryBuilder(api,Contribution.Side.COMMON);
        b.define(DocumentTypes.KIND);b.define(ResourceProducers.KIND);b.define(ResourceContentValidators.KIND);
        b.addModule(new ModuleSpec(OWNER,"1",api,Set.of(),Set.of()));b.addModule(new ModuleSpec(id("editor:resources"),"1",api,Set.of(),Set.of()));
        b.add(ResourceProducers.KIND,new Contribution(new ContributionKey(ResourceProducers.KIND.id(),IMPORTER),OWNER,Contribution.Side.COMMON,Set.of()),new ResourceProducerSpec(Set.of("1")));
        b.add(ResourceContentValidators.KIND,new Contribution(new ContributionKey(ResourceContentValidators.KIND.id(),IMPORTER),OWNER,Contribution.Side.COMMON,Set.of()),(version,content)->{
            content.copyContent(version.sourceEntry(),OutputStream.nullOutputStream());
            return adapter.rejectValidation?List.of(new Problem(Problem.Severity.ERROR,id("test:invalid"),"resource",LABEL)):List.of();
        });
        ResourceImporters.register(b,IMPORTER,OWNER,adapter);return b.freeze();
    }
    private static final class Queue implements Executor {
        Deque<Runnable> tasks=new ArrayDeque<>();boolean reject;
        public void execute(Runnable task){if(reject)throw new RejectedExecutionException();tasks.addLast(task);}
        void next(){tasks.removeFirst().run();}
    }
    private static final class Harness implements AutoCloseable {
        final Queue io=new Queue(),completion=new Queue();final ProjectWorkbench model;
        Harness(Adapter adapter){model=new ProjectWorkbench(registry(adapter),io,completion);}
        void inspect(Path path){model.inspectResourceImport(IMPORTER,Map.of("source",path));drain();assertTrue(model.resourceImportReady(),model.technicalFailure().toString());}
        void drain(){for(int i=0;!io.tasks.isEmpty()||!completion.tasks.isEmpty();i++){assertTrue(i<30);if(!io.tasks.isEmpty())io.next();if(!completion.tasks.isEmpty())completion.next();}assertFalse(model.busy());}
        public void close(){drain();if(model.hasProject()){model.closeProject(true);drain();}}
    }
    static BlobRef blob(byte[] bytes){try{return new BlobRef(BlobRef.Kind.ASSET,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),bytes.length);}catch(Exception e){throw new AssertionError(e);}}
    static EditorId id(String value){return EditorId.parse(value);}
}
