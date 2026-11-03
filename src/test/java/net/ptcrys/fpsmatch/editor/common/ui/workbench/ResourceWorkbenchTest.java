package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.api.extensions.*;
import net.ptcrys.fpsmatch.editor.api.registry.*;
import net.ptcrys.fpsmatch.editor.core.editing.*;
import net.ptcrys.fpsmatch.editor.core.resources.*;
import net.ptcrys.fpsmatch.editor.core.session.*;
import net.ptcrys.fpsmatch.editor.core.storage.*;
import net.ptcrys.fpsmatch.editor.core.types.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual disk resources and validator bytes; this synthetic format makes no weapon support claim. */
class ResourceWorkbenchTest {
    @TempDir Path temporary;
    private static final EditorId OWNER=id("test:resource_ui"),TYPE=id("test:note"),PRODUCER=id("test:raw"),ASSET=id("test:asset");
    private static final UUID DOCUMENT=uuid(1),RESOURCE=uuid(2),OLD=uuid(3),CURRENT=uuid(4),BYTE=uuid(5),NEXT=uuid(6);
    private static final TextContent LABEL=new TextContent.Literal("Resource note");

    @Test void documentResourceProjectionKeepsExactAppliedVersionsAcrossPendingInputAndHistory() throws Exception {
        Path path=temporary.resolve("locked-projection");project(path);
        AtomicInteger dependencyCalls=new AtomicInteger();
        var registry=registry(new ContextEditor(new ArrayList<>(),null),checked(new AtomicInteger()),true,dependencyCalls);
        DocumentHandle<?> observed;
        try(var h=new Harness(registry)) {
            assertTrue(h.model.resourcesForActiveDocument().isEmpty());
            h.model.open(path);h.drain();h.model.selectNode("root");
            assertEquals(List.of(),h.model.resourcesForActiveDocument().orElseThrow());
            var address=new ProjectWorkbench.Address(DOCUMENT,"root","value");
            h.model.input(address,"locked-old");h.model.apply(address);
            var old=h.model.resourcesForActiveDocument().orElseThrow();
            assertEquals(List.of(new ResourceRef(RESOURCE,OLD)),old);
            assertEquals(CURRENT,h.model.resources().orElseThrow().content().resources().get(RESOURCE).currentVersion());
            assertThrows(UnsupportedOperationException.class,old::clear);
            h.model.input(address,"locked-current");
            assertEquals(old,h.model.resourcesForActiveDocument().orElseThrow(),"unapplied raw cannot redirect the inspector reference");
            h.model.apply(address);
            assertEquals(List.of(new ResourceRef(RESOURCE,CURRENT)),h.model.resourcesForActiveDocument().orElseThrow());
            assertEquals(List.of(new ResourceRef(RESOURCE,OLD)),old,"a retained projection never follows later edits");
            h.model.undo();assertEquals(old,h.model.resourcesForActiveDocument().orElseThrow());
            h.model.redo();assertEquals(List.of(new ResourceRef(RESOURCE,CURRENT)),h.model.resourcesForActiveDocument().orElseThrow());
            observed=h.model.documents().get(DOCUMENT);
            int calls=dependencyCalls.get();
            assertEquals(Set.of(new ResourceRef(RESOURCE,CURRENT)),observed.currentDependencies().resources());
            assertEquals(h.model.resourcesForActiveDocument(),h.model.resourcesForActiveDocument());
            assertEquals(calls,dependencyCalls.get(),"display reads cannot rerun module dependency callbacks");
            var failure=new AtomicReference<Throwable>();
            Thread reader=new Thread(()->{try{observed.currentDependencies();}catch(Throwable caught){failure.set(caught);}});
            reader.start();reader.join(5000);assertFalse(reader.isAlive());assertInstanceOf(IllegalStateException.class,failure.get());
        }
        assertThrows(IllegalStateException.class,observed::currentDependencies);
    }

    @Test void pinnedDocumentResourceProjectionUsesItsOwnExactVersionWithoutChangingActiveSelection() throws Exception {
        Path path=temporary.resolve("pinned-resources");project(path);
        AtomicInteger calls=new AtomicInteger();
        var registry=registry(new ContextEditor(new ArrayList<>(),null),checked(new AtomicInteger()),true,calls);
        try(var h=new Harness(registry)) {
            h.model.open(path);h.drain();h.model.selectNode("root");
            var a=new ProjectWorkbench.Address(DOCUMENT,"root","value");
            h.model.input(a,"locked-old");h.model.apply(a);
            h.model.createDocument(TYPE,"Current reference");UUID other=h.model.activeDocument().orElseThrow();
            h.model.selectNode("root");var b=new ProjectWorkbench.Address(other,"root","value");
            h.model.input(b,"locked-current");h.model.apply(b);
            h.model.selectDocument(DOCUMENT);var before=h.model.documents().get(DOCUMENT).view();
            var pinned=h.model.resourcesForDocument(other).orElseThrow();
            assertEquals(List.of(new ResourceRef(RESOURCE,CURRENT)),pinned);
            assertEquals(List.of(new ResourceRef(RESOURCE,OLD)),h.model.resourcesForActiveDocument().orElseThrow());
            h.model.input(b,"locked-old");int reads=calls.get();
            assertEquals(pinned,h.model.resourcesForDocument(other).orElseThrow());
            assertEquals(reads,calls.get(),"a pinned panel reads the encoded dependency cache");
            assertEquals(DOCUMENT,h.model.activeDocument().orElseThrow());
            assertEquals("root",h.model.selectedNode().orElseThrow());
            h.model.apply(b);
            assertEquals(List.of(new ResourceRef(RESOURCE,OLD)),h.model.resourcesForDocument(other).orElseThrow());
            assertEquals(List.of(new ResourceRef(RESOURCE,CURRENT)),pinned,"retained pinned projection stays immutable");
            assertEquals(before,h.model.documents().get(DOCUMENT).view());
            assertEquals(DOCUMENT,h.model.activeDocument().orElseThrow());
            assertTrue(h.model.resourcesForDocument(UUID.randomUUID()).isEmpty());
        }
    }

    @Test void validatedOpeningBindsExactDirectoryAndSupportsEditHistorySaveAndDiskReopen() throws Exception {
        Path path=temporary.resolve("roundtrip");ResourceCatalog catalog=project(path);var contexts=new ArrayList<EditorResourceContext>();
        AtomicInteger reads=new AtomicInteger();var registry=registry(new ContextEditor(contexts,null),checked(reads),true);
        EditorResourceContext first;
        try(var h=new Harness(registry)) {
            h.model.open(path);h.drain();assertTrue(h.model.hasProject());assertFalse(h.model.protectedMode());assertEquals(2,reads.get());
            h.model.selectNode("root");h.model.fields();first=contexts.get(0);assertEquals(catalog,first.catalog().orElseThrow());
            var address=new ProjectWorkbench.Address(DOCUMENT,"root","value");h.model.input(address,"after");h.model.apply(address);
            h.model.undo();assertEquals("before",h.content());h.model.redo();assertEquals("after",h.content());
            h.model.saveAll();h.drain();assertFalse(h.model.unsaved());assertEquals(4,reads.get());
        }
        assertThrows(IllegalStateException.class,first::catalog);
        try(var h=new Harness(registry)) {
            h.model.open(path);h.drain();h.model.selectNode("root");h.model.fields();
            assertEquals("after",h.content());assertEquals(catalog,contexts.get(1).catalog().orElseThrow());assertNotSame(first,contexts.get(1));
        }
        try(var store=LocalProjectStore.open(path)) {
            assertEquals(catalog,ResourceStorageBridge.read(store,store.load().state().assets()));
            var bytes=new ByteArrayOutputStream();store.copyContent(catalog.byteEntries().get(BYTE).content(),bytes);
            assertEquals("actual asset bytes",bytes.toString(StandardCharsets.UTF_8));
        }
    }

    @Test void unsupportedOrRejectedContentStaysInspectableWithNoAuthenticatedDirectory() throws Exception {
        for(int mode=0;mode<3;mode++) {
            Path path=temporary.resolve("protected-"+mode);ResourceCatalog original=project(path);
            var contexts=new ArrayList<EditorResourceContext>();
            ResourceContentValidator validator=mode==0?null:(version,content)->List.of(error());
            var registry=registry(new ContextEditor(contexts,null),validator,mode!=2);
            SavedProject before;try(var store=LocalProjectStore.open(path)){before=store.load();}
            try(var h=new Harness(registry)) {
                h.model.open(path);h.drain();assertTrue(h.model.hasProject());assertTrue(h.model.protectedMode());
                assertEquals("before",h.content());h.model.selectNode("root");assertFalse(h.model.fields().isEmpty());
                assertTrue(contexts.get(0).catalog().isEmpty());assertFalse(h.model.problems().isEmpty());
                assertThrows(SessionRejection.class,()->h.model.createDocument(TYPE,"blocked"));
            }
            try(var store=LocalProjectStore.open(path)) {
                assertEquals(before,store.load());assertEquals(original,ResourceStorageBridge.read(store,store.load().state().assets()));
            }
        }
    }

    @Test void cancellationBeforeDuringAndAfterValidationReleasesWriterWithoutAdoptingAProject() throws Exception {
        for(int mode=0;mode<3;mode++) {
            Path path=temporary.resolve("cancel-"+mode);project(path);var model=new AtomicReference<ProjectWorkbench>();
            boolean during=mode==1;AtomicInteger calls=new AtomicInteger();
            var registry=registry(new ContextEditor(new ArrayList<>(),null),(version,content)->{
                calls.incrementAndGet();if(during){model.get().cancel();content.checkCancelled();}return List.of();
            },true);
            try(var h=new Harness(registry)) {
                model.set(h.model);h.model.open(path);
                if(mode==0)h.model.cancel();
                if(mode==2){h.io.next();h.model.cancel();}
                h.drain();assertFalse(h.model.hasProject());assertTrue(h.model.documents().isEmpty());
                assertTrue(h.model.directory().isEmpty());assertTrue(h.model.problems().isEmpty());
                assertEquals(ProjectWorkbench.text("cancelled"),h.model.status());
                assertEquals(mode==0?0:mode==1?1:2,calls.get());
            }
            try(var store=LocalProjectStore.open(path)){assertEquals("Resource fixture",store.load().state().name());}
        }
    }

    @Test void contextsRequireRealValidationExactRegistryOwnerThreadAndLiveSession() throws Exception {
        Path path=temporary.resolve("authority");ResourceCatalog catalog=project(path);
        var registry=registry(new ContextEditor(new ArrayList<>(),null),checked(new AtomicInteger()),true);
        var other=registry(new ContextEditor(new ArrayList<>(),null),checked(new AtomicInteger()),true);
        EditorResourceContext context;
        try(var store=LocalProjectStore.open(path)) {
            var access=new LocalProjectAccess(store);
            try(var session=ProjectSession.open(access,access.read(),registry,2)){assertTrue(session.editorResources().catalog().isEmpty());}
            var validated=access.readValidated(registry,()->false);
            try(var session=ProjectSession.open(access,validated,other,2)){assertTrue(session.editorResources().catalog().isEmpty());}
            try(var session=ProjectSession.open(access,validated,registry,2)) {
                context=session.editorResources();assertSame(context,session.editorResources());assertEquals(catalog,context.catalog().orElseThrow());
                assertThrows(UnsupportedOperationException.class,()->context.catalog().orElseThrow().versions().clear());
                assertThrows(IllegalArgumentException.class,()->DocumentEditors.find(other,TYPE,String.class,context));
                var failure=new AtomicReference<Throwable>();Thread thread=new Thread(()->{try{context.catalog();}catch(Throwable caught){failure.set(caught);}});
                thread.start();thread.join(5000);assertFalse(thread.isAlive());assertInstanceOf(IllegalStateException.class,failure.get());
                failure.set(null);Thread bindThread=new Thread(()->{try{context.requireRegistry(registry);}catch(Throwable caught){failure.set(caught);}});
                bindThread.start();bindThread.join(5000);assertFalse(bindThread.isAlive());assertInstanceOf(IllegalStateException.class,failure.get());
            }
        }
        assertThrows(IllegalStateException.class,context::catalog);assertThrows(IllegalStateException.class,()->context.requireRegistry(registry));
        assertThrows(IllegalStateException.class,()->DocumentEditors.find(registry,TYPE,String.class,context));
    }

    @Test void realIoThreadValidationCooperatesWithOwnerCancellationAndReleasesStore() throws Exception {
        Path path=temporary.resolve("threaded-cancel");project(path);
        Thread owner=Thread.currentThread();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var completions=new LinkedBlockingQueue<Runnable>();var worker=Executors.newSingleThreadExecutor();
        var registry=registry(new ContextEditor(new ArrayList<>(),null),(version,content)->{
            assertNotSame(owner,Thread.currentThread());entered.countDown();
            try {if(!release.await(5,TimeUnit.SECONDS))throw new java.io.IOException("release timed out");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.IOException(interrupted);}
            content.checkCancelled();return List.of();
        },true);
        var model=new ProjectWorkbench(registry,worker,completions::add);
        try {
            model.open(path);assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(model.canCancel());
            model.cancel();release.countDown();var completed=completions.poll(5,TimeUnit.SECONDS);assertNotNull(completed);completed.run();
            assertFalse(model.busy());assertFalse(model.hasProject());assertTrue(model.technicalFailure().isEmpty());
            assertTrue(model.problems().isEmpty());assertEquals(ProjectWorkbench.text("cancelled"),model.status());
            try(var store=LocalProjectStore.open(path)){assertEquals("Resource fixture",store.load().state().name());}
        } finally {release.countDown();worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test void resourceRenameAndNewCurrentRemainExcludedUntilValidatedSaveAndReopen() throws Exception {
        Path path=temporary.resolve("frozen");ResourceCatalog original=project(path);
        var registry=registry(new ContextEditor(new ArrayList<>(),null),checked(new AtomicInteger()),true);
        ResourceCatalog changed;EditorResourceContext frozen;
        try(var store=LocalProjectStore.open(path)) {
            var access=new LocalProjectAccess(store);
            try(var session=ProjectSession.open(access,access.readValidated(registry,()->false),registry,4)) {
                frozen=session.editorResources();var handle=session.resources().orElseThrow();
                var versions=new HashMap<>(original.versions());versions.put(NEXT,version(NEXT));
                changed=new ResourceCatalog(Map.of(RESOURCE,new LogicalResource(RESOURCE,ASSET,"Renamed",NEXT)),versions,original.byteEntries());
                handle.edit(handle.view().token(),LABEL,ignored->changed);assertEquals(original,frozen.catalog().orElseThrow());
                handle.undo(handle.view().token());assertEquals(original,frozen.catalog().orElseThrow());
                handle.redo(handle.view().token());assertEquals(original,frozen.catalog().orElseThrow());
                session.enqueueSave(access.prepareValidated(session.captureSave(Set.of(),true),()->false));
                var job=session.dispatchSave().orElseThrow().job().orElseThrow();assertTrue(session.completeSave(job,access.publish(job)));
                assertEquals(original,frozen.catalog().orElseThrow());assertEquals(original.versions().get(OLD),changed.versions().get(OLD));
            }
        }
        assertThrows(IllegalStateException.class,frozen::catalog);
        try(var store=LocalProjectStore.open(path)) {
            var access=new LocalProjectAccess(store);
            try(var session=ProjectSession.open(access,access.readValidated(registry,()->false),registry,2)) {
                assertEquals(changed,session.editorResources().catalog().orElseThrow());assertNotSame(frozen,session.editorResources());
            }
        }
    }

    @Test void emptyValidatedDirectoryIsDistinctFromUnavailableAndLegacyBindStillRuns() throws Exception {
        Path path=temporary.resolve("empty");
        try(var store=LocalProjectStore.create(path)){store.commit(null,UUID.randomUUID(),new ProjectState(UUID.randomUUID(),"Empty",Map.of(),Map.of(),Map.of(),null));}
        AtomicInteger legacyBindings=new AtomicInteger();
        DocumentEditor<String> legacy=new DocumentEditor<>(){
            public Class<String> valueType(){return String.class;}public TextContent label(){return LABEL;}
            @Override public DocumentEditor<String> bind(ExtensionRegistry registry){legacyBindings.incrementAndGet();return this;}
            public List<Node> nodes(String value){return List.of();}public List<Field> fields(String value,String node){return List.of();}
            public String apply(String value,String node,String property,String raw){return raw;}
        };
        var registry=registry(legacy,checked(new AtomicInteger()),true);
        try(var store=LocalProjectStore.open(path)) {
            var access=new LocalProjectAccess(store);
            try(var session=ProjectSession.open(access,access.readValidated(registry,()->false),registry,2)) {
                assertTrue(session.editorResources().catalog().orElseThrow().versions().isEmpty());
                assertSame(legacy,DocumentEditors.find(registry,TYPE,String.class,session.editorResources()).orElseThrow());assertEquals(1,legacyBindings.get());
            }
        }
    }

    private static ResourceContentValidator checked(AtomicInteger calls){return (version,content)->{
        calls.incrementAndGet();var bytes=new ByteArrayOutputStream();content.copyContent(version.sourceEntry(),bytes);
        return bytes.toString(StandardCharsets.UTF_8).equals("actual asset bytes")?List.of():List.of(error());
    };}
    private static Problem error(){return new Problem(Problem.Severity.ERROR,id("test:bad_asset"),RESOURCE.toString(),LABEL);}
    private static ResourceCatalog project(Path path) throws Exception {
        try(var store=LocalProjectStore.create(path)) {
            var source=new ProjectState.Entry(BYTE,OWNER.toString(),ASSET.toString(),PRODUCER.toString(),1,"Source",
                    store.stage(BlobRef.Kind.ASSET,"actual asset bytes".getBytes(StandardCharsets.UTF_8)),Set.of(),Set.of());
            var catalog=new ResourceCatalog(Map.of(RESOURCE,new LogicalResource(RESOURCE,ASSET,"Original",CURRENT)),
                    Map.of(OLD,version(OLD),CURRENT,version(CURRENT)),Map.of(BYTE,source));
            var document=new ProjectState.Entry(DOCUMENT,OWNER.toString(),TYPE.toString(),TYPE.toString(),1,"Note",
                    store.stage(BlobRef.Kind.DOCUMENT,"before".getBytes(StandardCharsets.UTF_8)),Set.of(),Set.of());
            store.commit(null,UUID.randomUUID(),new ProjectState(UUID.randomUUID(),"Resource fixture",Map.of(OWNER.toString(),"1","editor:resources","1"),
                    Map.of(DOCUMENT,document),ResourceStorageBridge.stage(store,ResourceStorageBridge.planAll(catalog)),null));return catalog;
        }
    }
    private static ResourceVersion version(UUID version){return new ResourceVersion(version,RESOURCE,PRODUCER,"1",Map.of(),BYTE,Set.of(),Set.of());}
    private static ExtensionRegistry registry(DocumentEditor<String> editor,ResourceContentValidator validator,boolean producer) {
        return registry(editor,validator,producer,new AtomicInteger());
    }
    private static ExtensionRegistry registry(DocumentEditor<String> editor,ResourceContentValidator validator,boolean producer,AtomicInteger dependencyCalls) {
        var api=new ApiVersion(1,0);var builder=new ExtensionRegistryBuilder(api,Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND);builder.define(ResourceProducers.KIND);builder.define(ResourceContentValidators.KIND);
        builder.addModule(new ModuleSpec(OWNER,"1",api,Set.of(),Set.of()));builder.addModule(new ModuleSpec(id("editor:resources"),"1",api,Set.of(),Set.of()));
        builder.add(DocumentTypes.KIND,contribution(DocumentTypes.KIND,TYPE),new NoteType(dependencyCalls));DocumentEditors.register(builder,TYPE,OWNER,editor);
        if(producer)builder.add(ResourceProducers.KIND,contribution(ResourceProducers.KIND,PRODUCER),new ResourceProducerSpec(Set.of("1")));
        if(validator!=null)builder.add(ResourceContentValidators.KIND,contribution(ResourceContentValidators.KIND,PRODUCER),validator);return builder.freeze();
    }
    private static Contribution contribution(ExtensionKind<?> kind,EditorId type){return new Contribution(new ContributionKey(kind.id(),type),OWNER,Contribution.Side.COMMON,Set.of());}
    private static class ContextEditor implements DocumentEditor<String> {
        final List<EditorResourceContext> seen;final EditorResourceContext context;
        ContextEditor(List<EditorResourceContext> seen,EditorResourceContext context){this.seen=seen;this.context=context;}
        public Class<String> valueType(){return String.class;}public TextContent label(){return LABEL;}
        @Override public DocumentEditor<String> bind(ExtensionRegistry registry,EditorResourceContext resources){seen.add(resources);return new ContextEditor(seen,resources);}
        public List<Node> nodes(String value){return List.of(new Node("root",Optional.empty(),LABEL));}
        public List<Field> fields(String value,String node){context.catalog();return List.of(new Field("value",LABEL,Kind.TEXT,value,LABEL));}
        public String apply(String value,String node,String property,String raw){context.catalog();return raw;}
    }
    private static final class NoteType implements DocumentType<String> {
        private final AtomicInteger dependencyCalls;
        private NoteType(AtomicInteger dependencyCalls){this.dependencyCalls=dependencyCalls;}
        public Class<String> valueType(){return String.class;}public EditorId codecId(){return TYPE;}public int schemaVersion(){return 1;}
        public Set<Integer> readableSchemas(){return Set.of(1);}public String create(){return "before";}public String snapshot(String value){return value;}
        public byte[] encode(String value){return value.getBytes(StandardCharsets.UTF_8);}public String decode(int schema,byte[] bytes){return new String(bytes,StandardCharsets.UTF_8);}
        public List<Problem> validate(String value){return List.of();}
        public DocumentDependencies dependencies(String value){
            dependencyCalls.incrementAndGet();
            var references=switch(value){case "locked-old"->Set.of(new ResourceRef(RESOURCE,OLD));case "locked-current"->Set.of(new ResourceRef(RESOURCE,CURRENT));default->Set.<ResourceRef>of();};
            return new DocumentDependencies(Set.of(),references,Set.of());
        }
    }
    private static final class ManualExecutor implements Executor {
        final Deque<Runnable> tasks=new ArrayDeque<>();public void execute(Runnable task){tasks.addLast(task);}void next(){tasks.removeFirst().run();}
    }
    private static final class Harness implements AutoCloseable {
        final ManualExecutor io=new ManualExecutor(),completion=new ManualExecutor();final ProjectWorkbench model;
        Harness(ExtensionRegistry registry){model=new ProjectWorkbench(registry,io,completion);}
        Object content(){return model.documents().get(DOCUMENT).view().content();}
        void drain(){for(int i=0;!io.tasks.isEmpty()||!completion.tasks.isEmpty();i++){assertTrue(i<32);if(!io.tasks.isEmpty())io.next();if(!completion.tasks.isEmpty())completion.next();}assertFalse(model.busy());assertTrue(model.technicalFailure().isEmpty(),model.technicalFailure().toString());}
        public void close(){drain();if(model.hasProject()){model.closeProject(true);drain();}}
    }
    private static EditorId id(String value){return EditorId.parse(value);}private static UUID uuid(long value){return new UUID(0x82520000L,value);}
}
