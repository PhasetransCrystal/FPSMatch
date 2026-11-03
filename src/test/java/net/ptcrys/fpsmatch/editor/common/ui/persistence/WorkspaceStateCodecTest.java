package net.ptcrys.fpsmatch.editor.common.ui.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.common.ui.model.*;
import org.junit.jupiter.api.Test;

class WorkspaceStateCodecTest {
    static WorkspaceState sample(String name){
        var opaque=new LinkedHashMap<String,String>();opaque.put("Key","1");opaque.put("key","2");opaque.put(""," unfinished 12e ");opaque.put("dock.group","leftBottom");
        var panel=new PanelInstance(UUID.randomUUID(),EditorId.parse("missing:inspector"),PanelBinding.pinned(UUID.randomUUID()),opaque);
        var removed=new PanelInstance(UUID.randomUUID(),EditorId.parse("missing:removed"),PanelBinding.followActive(),Map.of("saved-only","preserve"));
        var workspace=new WorkspaceInstance(UUID.randomUUID(),EditorId.parse("missing:template"),7,name,true,List.of(panel));
        return new WorkspaceState(List.of(workspace),workspace.id(),Map.of(workspace.id(),List.of(removed,panel)));
    }
    static WorkspaceState oversized(){
        var state=sample("Large");var workspace=state.workspaces().get(0);var panel=workspace.panels().get(0);var values=new LinkedHashMap<String,String>();String value="a".repeat(65536);for(int i=0;i<130;i++)values.put("key"+i,value);
        var large=new PanelInstance(panel.id(),panel.type(),panel.binding(),values);var changed=new WorkspaceInstance(workspace.id(),workspace.templateId(),workspace.templateVersion(),workspace.name(),true,List.of(large));
        return new WorkspaceState(List.of(changed),changed.id(),Map.of(changed.id(),List.of()));
    }
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static String text(WorkspaceState state){return new String(WorkspaceStateCodec.encode(state),StandardCharsets.UTF_8);}
    private static byte[] changed(WorkspaceState state,Consumer<JsonObject> edit){var json=JsonParser.parseString(text(state)).getAsJsonObject();edit.accept(json);return bytes(json.toString());}
    private static JsonObject panel(JsonObject json){return json.getAsJsonArray("workspaces").get(0).getAsJsonObject().getAsJsonArray("panels").get(0).getAsJsonObject();}
    @Test void currentAndSavedStateRoundTripWithoutInstalledContributions(){
        var state=sample("工作区");byte[] encoded=WorkspaceStateCodec.encode(state);var decoded=WorkspaceStateCodec.decode(encoded);assertEquals(state,decoded);assertArrayEquals(encoded,WorkspaceStateCodec.encode(decoded));
        assertEquals(List.of("Key","key","","dock.group"),List.copyOf(decoded.workspaces().get(0).panels().get(0).viewState().keySet()));
        assertEquals(new WorkspaceState(List.of(),null,Map.of()),WorkspaceStateCodec.decode(WorkspaceStateCodec.encode(new WorkspaceState(List.of(),null,Map.of()))));
    }
    @Test void fullLengthUnicodeViewStateSurvivesStrictStringBudgetAndChunkBoundaries(){
        var state=sample("Unicode");var workspace=state.workspaces().get(0);var panel=workspace.panels().get(0);
        String value="a".repeat(16383)+"🚀"+"汉".repeat(49151);assertEquals(65536,value.length());
        var rich=new PanelInstance(panel.id(),panel.type(),panel.binding(),Map.of("键".repeat(256),value));var changed=new WorkspaceInstance(workspace.id(),workspace.templateId(),workspace.templateVersion(),workspace.name(),true,List.of(rich));
        var input=new WorkspaceState(List.of(changed),changed.id(),Map.of(changed.id(),List.of(rich)));assertEquals(input,WorkspaceStateCodec.decode(WorkspaceStateCodec.encode(input)));
    }
    @Test void strictSyntaxRejectsDuplicateKeysCommentsTrailingRootsAndMalformedUtf8(){
        String valid=text(sample("One"));
        for(String bad:List.of(valid.replace("\"formatVersion\":1","\"formatVersion\":1,\"formatVersion\":1"),"/*comment*/"+valid,valid+" {}",valid.substring(0,valid.length()-1)+",}"))assertThrows(WorkspaceStateCodec.CodecException.class,()->WorkspaceStateCodec.decode(bytes(bad)));
        assertThrows(WorkspaceStateCodec.CodecException.class,()->WorkspaceStateCodec.decode(new byte[]{(byte)0xc3,(byte)0x28}));
        assertThrows(WorkspaceStateCodec.CodecException.class,()->WorkspaceStateCodec.decode(bytes(valid.replace("One",String.valueOf((char)92)+"uD800"))));
    }
    @Test void exactMemberTypesAndUnknownVersionsAreProtectedInsteadOfCoercedOrRepaired(){
        var state=sample("One");
        var unsupported=assertThrows(WorkspaceStateCodec.CodecException.class,()->WorkspaceStateCodec.decode(changed(state,json->json.addProperty("formatVersion",2))));assertEquals(WorkspaceStateCodec.Reason.UNSUPPORTED_VERSION,unsupported.reason());
        assertThrows(WorkspaceStateCodec.CodecException.class,()->WorkspaceStateCodec.decode(changed(state,json->json.addProperty("formatVersion","1"))));
        assertThrows(WorkspaceStateCodec.CodecException.class,()->WorkspaceStateCodec.decode(changed(state,json->json.addProperty("future",true))));
        assertThrows(WorkspaceStateCodec.CodecException.class,()->WorkspaceStateCodec.decode(changed(state,json->json.remove("savedLayouts"))));
        assertThrows(WorkspaceStateCodec.CodecException.class,()->WorkspaceStateCodec.decode(changed(state,json->json.getAsJsonArray("workspaces").get(0).getAsJsonObject().addProperty("visible","true"))));
        assertThrows(WorkspaceStateCodec.CodecException.class,()->WorkspaceStateCodec.decode(changed(state,json->panel(json).addProperty("id","1-1-1-1-1"))));
        assertThrows(WorkspaceStateCodec.CodecException.class,()->WorkspaceStateCodec.decode(changed(state,json->panel(json).getAsJsonObject("binding").addProperty("mode","FOLLOW_ACTIVE"))));
    }
    @Test void duplicateOpaqueKeysInvalidBaselinesAndMissingActiveIdentityDoNotPartiallyDecode(){
        var state=sample("One");
        assertThrows(WorkspaceStateCodec.CodecException.class,()->WorkspaceStateCodec.decode(changed(state,json->{var values=panel(json).getAsJsonArray("viewState");values.add(values.get(0).deepCopy());})));
        assertThrows(WorkspaceStateCodec.CodecException.class,()->WorkspaceStateCodec.decode(changed(state,json->{var saved=json.getAsJsonArray("savedLayouts");saved.add(saved.get(0).deepCopy());})));
        assertThrows(WorkspaceStateCodec.CodecException.class,()->WorkspaceStateCodec.decode(changed(state,json->json.addProperty("activeWorkspace",UUID.randomUUID().toString()))));
        assertThrows(WorkspaceStateCodec.CodecException.class,()->WorkspaceStateCodec.decode(changed(state,json->panel(json).getAsJsonArray("viewState").get(0).getAsJsonObject().add("value",new JsonArray()))));
    }
    @Test void inputAndOutputHaveAggregateBudgetsAndEncodingRejectsBrokenUnicode(){
        var input=assertThrows(WorkspaceStateCodec.CodecException.class,()->WorkspaceStateCodec.decode(new byte[WorkspaceStateCodec.MAX_BYTES+1]));assertEquals(WorkspaceStateCodec.Reason.LIMIT_EXCEEDED,input.reason());
        var output=assertThrows(WorkspaceStateCodec.CodecException.class,()->WorkspaceStateCodec.encode(oversized()));assertEquals(WorkspaceStateCodec.Reason.LIMIT_EXCEEDED,output.reason());
        var broken=sample("broken"+Character.toString((char)0xd800));assertThrows(WorkspaceStateCodec.CodecException.class,()->WorkspaceStateCodec.encode(broken));
    }
}
