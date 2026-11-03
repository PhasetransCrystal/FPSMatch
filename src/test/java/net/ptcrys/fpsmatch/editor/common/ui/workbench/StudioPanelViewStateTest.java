package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.codec.JsonContractException;
import org.junit.jupiter.api.Test;

class StudioPanelViewStateTest {
    private static final List<String> INTS=List.of("objectScroll","fieldScroll","resourceScroll","resourceDetailScroll","taskScroll","projectScroll");
    private static final List<String> STRINGS=List.of("inspectorSearch","resourceSearch","resourceTab","task","projectSearch");
    private static final List<String> INT_MAPS=List.of("objectContextScroll","fieldContextScroll");
    private static final List<String> STRING_MAPS=List.of("resourceVersions","objectSearch","inspectorPages");
    private static final List<String> SET_MAPS=List.of("objectCollapsed","inspectorCollapsed");
    private static Map<String,String> parts(String... chunks){
        var state=new LinkedHashMap<String,String>();state.put("ui.parts",Integer.toString(chunks.length));
        for(int i=0;i<chunks.length;i++)state.put("ui."+i,chunks[i]);return state;
    }
    private static Map<String,String> encoded(String json){
        var chunks=new ArrayList<String>();for(int i=0;i<json.length();i+=60000)chunks.add(json.substring(i,Math.min(json.length(),i+60000)));
        return parts(chunks.toArray(String[]::new));
    }
    private static void badField(String name,String value){
        var failure=assertThrows(IllegalArgumentException.class,()->StudioPanelViewState.decode(encoded("{\""+name+"\":"+value+"}")));
        assertTrue(failure.getMessage().contains("/"+name),"Failure must identify the host field");
    }
    private static JsonContractException badJson(String json,String code){
        var failure=assertThrows(JsonContractException.class,()->StudioPanelViewState.decode(encoded(json)));
        assertEquals(code,failure.code());return failure;
    }

    @Test void absentManifestIsEmptyAndLegacyEmptyObjectIsPresent(){
        assertTrue(StudioPanelViewState.decode(Map.of()).isEmpty());
        assertTrue(StudioPanelViewState.decode(Map.of("dock.group","left","ui.future","opaque text")).isEmpty());
        assertThrows(IllegalArgumentException.class,()->StudioPanelViewState.decode(Map.of("ui.0","orphaned host text")));
        assertTrue(StudioPanelViewState.decode(encoded("{}")).orElseThrow().entrySet().isEmpty());
        assertThrows(IllegalArgumentException.class,()->StudioPanelViewState.decode(null));
    }

    @Test void completeLegacyStateAndNewProjectFieldsKeepTheirOriginalValues(){
        String json="""
            {"objectContextScroll":{"doc/a":3,"doc/b":-2},"fieldContextScroll":{"doc/a":4},
             "resourceVersions":{"resource/a":"v3"},"objectSearch":{"doc/a":"检索"},
             "objectCollapsed":{"doc/a":["root","root","child"]},
             "inspectorPages":{"doc/a/node":"resources"},"inspectorCollapsed":{"doc/a/node":["section"]},
             "objectScroll":-3,"fieldScroll":0,"inspectorSearch":"字段","resourceSearch":"素材",
             "resourceTab":"preview","resourceScroll":1,"resourceDetailScroll":2,"task":"project","taskScroll":3,
             "resource":"a2ebce46-9870-43cd-9728-e4311d3aaa15","active":true,"projectSearch":"工程","projectScroll":6}
            """;
        var wire=encoded(json);wire.put("dock.group","leftTop");var before=new LinkedHashMap<>(wire);
        var decoded=StudioPanelViewState.decode(wire).orElseThrow();
        assertEquals(ExactJson.parse(json),ExactJson.freeze(decoded));assertEquals(before,wire);
        assertEquals(-3,decoded.get("objectScroll").getAsInt());assertEquals(-2,decoded.getAsJsonObject("objectContextScroll").get("doc/b").getAsInt());
        assertEquals(3,decoded.getAsJsonObject("objectCollapsed").getAsJsonArray("doc/a").size(),"Legacy set projection may deduplicate only after validation");
        assertEquals("工程",decoded.get("projectSearch").getAsString());assertEquals(6,decoded.get("projectScroll").getAsInt());
    }

    @Test void exactIntValidationRejectsCoercionFractionsAndBothOverflowDirections(){
        for(String name:INTS)for(String value:List.of("null","true","\"1\"","[]","{}","1.5","2147483648","-2147483649","1e100","1e-100"))badField(name,value);
        for(String name:INT_MAPS)for(String value:List.of("null","false","\"1\"","[]","{}","2.25","2147483648","-2147483649"))badField(name,"{\"context\":"+value+"}");
        for(String value:List.of("-2147483648","2147483647","1.0","3e2","-0")){
            var decoded=StudioPanelViewState.decode(encoded("{\"projectScroll\":"+value+",\"objectContextScroll\":{\"context\":"+value+"}}")).orElseThrow();
            assertEquals(new BigDecimal(value).intValueExact(),decoded.get("projectScroll").getAsBigDecimal().intValueExact());
        }
    }

    @Test void everyStringBooleanMappingAndSetUsesExactJsonTypes(){
        for(String name:STRINGS)for(String value:List.of("null","1","true","[]","{}"))badField(name,value);
        for(String name:STRING_MAPS)for(String value:List.of("null","1","true","[]","{}"))badField(name,"{\"context\":"+value+"}");
        for(String name:SET_MAPS){
            for(String value:List.of("null","1","true","\"collapsed\"","{}","[\"ok\",false]","[\"ok\",null]","[[]]"))badField(name,"{\"context\":"+value+"}");
        }
        var maps=new ArrayList<>(INT_MAPS);maps.addAll(STRING_MAPS);maps.addAll(SET_MAPS);
        for(String name:maps)for(String value:List.of("null","1","true","\"text\"","[]"))badField(name,value);
        for(String value:List.of("null","0","1","\"true\"","[]","{}"))badField("active",value);
        assertFalse(StudioPanelViewState.decode(encoded("{\"active\":false}")).orElseThrow().get("active").getAsBoolean());
    }

    @Test void resourceMustBeAStandardUuidStringWithoutJavasShortFormAcceptance(){
        for(String value:List.of("null","1","true","[]","{}","\"1-1-1-1-1\"","\"not-a-uuid\"","\"a2ebce46-9870-43cd-9728-e4311d3aaa1z\""))badField("resource",value);
        String upper="A2EBCE46-9870-43CD-9728-E4311D3AAA15";
        assertEquals(upper,StudioPanelViewState.decode(encoded("{\"resource\":\""+upper+"\"}")).orElseThrow().get("resource").getAsString());
    }

    @Test void lateUuidOrMappingFailureCannotPublishAPartiallyDecodedState(){
        for(String suffix:List.of("\"resource\":\"1-2-3-4-5\"","\"resourceVersions\":{\"first\":\"v1\",\"late\":false}","\"fieldContextScroll\":{\"first\":4,\"late\":3.5}")){
            var wire=encoded("{\"objectSearch\":{\"document\":\"new search\"},\"objectScroll\":12,"+suffix+"}");var before=new LinkedHashMap<>(wire);
            var applied=new AtomicInteger();var cache=new HashMap<>(Map.of("document","existing search"));
            assertThrows(IllegalArgumentException.class,()->StudioPanelViewState.decode(wire).ifPresent(state->{applied.incrementAndGet();cache.put("document",state.getAsJsonObject("objectSearch").get("document").getAsString());}));
            assertEquals(0,applied.get());assertEquals(Map.of("document","existing search"),cache);assertEquals(before,wire);
        }
    }

    @Test void chunkManifestRejectsMissingEmptyExtraAndNoncanonicalChunks(){
        for(String count:List.of("","0","-1","129","01","+1"," 1","1.0","2147483648"))assertThrows(IllegalArgumentException.class,()->StudioPanelViewState.decode(Map.of("ui.parts",count,"ui.0","{}")));
        assertThrows(IllegalArgumentException.class,()->StudioPanelViewState.decode(Map.of("ui.parts","3","ui.0","{","ui.2","}")));
        assertThrows(IllegalArgumentException.class,()->StudioPanelViewState.decode(parts("{}","")));
        var nullManifest=parts("{}");nullManifest.put("ui.parts",null);assertThrows(IllegalArgumentException.class,()->StudioPanelViewState.decode(nullManifest));
        var nullChunk=parts("{}");nullChunk.put("ui.0",null);assertThrows(IllegalArgumentException.class,()->StudioPanelViewState.decode(nullChunk));
        for(String extra:List.of("ui.1","ui.01","ui.-1","ui.+1")){var wire=parts("{}");wire.put(extra,"ignored before");assertThrows(IllegalArgumentException.class,()->StudioPanelViewState.decode(wire));}
        var extension=parts("{}");extension.put("ui.future","kept");assertTrue(StudioPanelViewState.decode(extension).isPresent());assertEquals("kept",extension.get("ui.future"));
        var reversed=new LinkedHashMap<String,String>();reversed.put("ui.1","true}");reversed.put("ui.parts","2");reversed.put("ui.0","{\"active\":");
        assertTrue(StudioPanelViewState.decode(reversed).orElseThrow().get("active").getAsBoolean());
    }

    @Test void unicodeSurrogatePairsAndJsonEscapesMayCrossChunkBoundaries(){
        String raw="{\"resourceSearch\":\"检索😀结果\",\"future\":{\"标签\":\"🌍\"}}";int cut=raw.indexOf("😀")+1;
        var split=parts(raw.substring(0,cut),raw.substring(cut));
        assertEquals(ExactJson.parse(raw),ExactJson.freeze(StudioPanelViewState.decode(split).orElseThrow()));
        String escaped="{\"resourceSearch\":\"\\uD83D\\uDE00\"}";int escapeCut=escaped.indexOf("\\uDE00")+3;
        assertEquals("😀",StudioPanelViewState.decode(parts(escaped.substring(0,escapeCut),escaped.substring(escapeCut))).orElseThrow().get("resourceSearch").getAsString());
        var failure=assertThrows(JsonContractException.class,()->StudioPanelViewState.decode(parts("{\"resourceSearch\":\""+'\uD83D',"\"}")));assertEquals("INVALID_UNICODE",failure.code());
    }

    @Test void unknownJsonMembersRemainExactAndReturnedTreesAreIndependent(){
        String json="{\"objectSearch\":{\"doc\":\"kept\"},\"future\":{\"items\":[{\"value\":9007199254740993.125},null,true],\"tag\":\"扩展😀\"}}";
        var wire=encoded(json);var before=new LinkedHashMap<>(wire);var decoded=StudioPanelViewState.decode(wire).orElseThrow();
        assertEquals(ExactJson.parse(json),ExactJson.freeze(decoded));
        decoded.getAsJsonObject("future").addProperty("tag","changed");decoded.getAsJsonObject("future").getAsJsonArray("items").get(0).getAsJsonObject().addProperty("value",0);
        decoded.getAsJsonObject("objectSearch").addProperty("doc","changed");
        var again=StudioPanelViewState.decode(wire).orElseThrow();assertNotSame(decoded,again);assertEquals(ExactJson.parse(json),ExactJson.freeze(again));assertEquals(before,wire);
    }

    @Test void strictJsonRejectsRootShapesTrailingSyntaxAndDuplicateDecodedKeys(){
        for(String json:List.of("null","[]","true","1","\"state\"","{} {}","{active:true}","{\"active\":true,}"))assertThrows(IllegalArgumentException.class,()->StudioPanelViewState.decode(encoded(json)));
        badJson("{\"active\":true,\"active\":false}","DUPLICATE_KEY");
        badJson("{\"future\":{\"a\":1,\"\\u0061\":2}}","DUPLICATE_KEY");
        badJson("{\"objectSearch\":{\"doc\":\"a\",\"doc\":\"b\"}}","DUPLICATE_KEY");
    }

    @Test void exactJsonDepthWholeTextUtf8AndDecodedStringBudgetsAreReused(){
        badJson("{\"future\":"+"[".repeat(ExactJson.MAX_DEPTH+1)+"0"+"]".repeat(ExactJson.MAX_DEPTH+1)+"}","LIMIT_EXCEEDED");
        badJson("{\"resourceSearch\":\""+"a".repeat(ExactJson.MAX_STRING_UTF8_BYTES+1)+"\"}","LIMIT_EXCEEDED");
        String oversized="{\"future\":\""+"中".repeat(ExactJson.MAX_UTF8_BYTES/3+1)+"\"}";
        assertTrue(oversized.length()<ExactJson.MAX_UTF8_BYTES,"The input exceeds the byte budget without exceeding the character preflight");
        badJson(oversized,"LIMIT_EXCEEDED");
    }

    @Test void listScrollVersionAcceptsOnlyExactOneAndPreservesLegacyAbsence(){
        for(String value:List.of("null","true","\"1\"","[]","{}","0","-1","2","1.5","2147483648","1e100"))badField("listScrollVersion",value);
        for(String value:List.of("1","1.0","1e0")){
            var state=StudioPanelViewState.decode(encoded("{\"listScrollVersion\":"+value+",\"objectScroll\":14}")).orElseThrow();
            assertEquals(1,state.get("listScrollVersion").getAsBigDecimal().intValueExact());
            assertEquals(14,StudioPanelViewState.listScrollOffset(state,14,22,78));
        }
        assertFalse(StudioPanelViewState.decode(encoded("{\"objectScroll\":3}")).orElseThrow().has("listScrollVersion"));
    }

    @Test void legacyListRowsConvertToPixelsWithoutMovingTheTopOrMutatingJson(){
        var state=StudioPanelViewState.decode(encoded("{\"objectScroll\":3,\"objectContextScroll\":{\"doc\":4},\"projectScroll\":2,\"future\":{\"kept\":true}}")).orElseThrow();
        var before=state.deepCopy();
        assertEquals(144,StudioPanelViewState.listScrollOffset(state,state.get("objectScroll").getAsInt(),22,78));
        assertEquals(166,StudioPanelViewState.listScrollOffset(state,state.getAsJsonObject("objectContextScroll").get("doc").getAsInt(),22,78));
        assertEquals(102,StudioPanelViewState.listScrollOffset(state,state.get("projectScroll").getAsInt(),24,54));
        for(int value:List.of(0,-1,Integer.MIN_VALUE))assertEquals(0,StudioPanelViewState.listScrollOffset(state,value,22,78));
        assertEquals(before,state);assertFalse(state.has("listScrollVersion"));
    }

    @Test void listRowConversionSaturatesAtTheIntBoundaryWithoutArithmeticWrap(){
        var legacy=StudioPanelViewState.decode(encoded("{}")).orElseThrow();
        int last=(Integer.MAX_VALUE-78)/22;
        assertEquals(78L+(long)last*22,StudioPanelViewState.listScrollOffset(legacy,last,22,78));
        assertEquals(Integer.MAX_VALUE,StudioPanelViewState.listScrollOffset(legacy,last+1,22,78));
        assertEquals(Integer.MAX_VALUE,StudioPanelViewState.listScrollOffset(legacy,Integer.MAX_VALUE,22,78));
        assertEquals(Integer.MAX_VALUE,StudioPanelViewState.listScrollOffset(legacy,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE));
    }

    @Test void persistedVersionOneKeepsPixelsAndNeverAppliesLegacyChromeTwice(){
        var legacy=StudioPanelViewState.decode(encoded("{\"objectScroll\":3}")).orElseThrow();
        int converted=StudioPanelViewState.listScrollOffset(legacy,3,22,78);
        var persisted=legacy.deepCopy();persisted.addProperty("objectScroll",converted);persisted.addProperty("listScrollVersion",1);
        var current=StudioPanelViewState.decode(encoded(persisted.toString())).orElseThrow();var before=current.deepCopy();
        assertEquals(converted,StudioPanelViewState.listScrollOffset(current,current.get("objectScroll").getAsInt(),22,78));
        for(int value:List.of(0,1,7,Integer.MAX_VALUE))assertEquals(value,StudioPanelViewState.listScrollOffset(current,value,24,54));
        for(int value:List.of(-1,Integer.MIN_VALUE))assertEquals(0,StudioPanelViewState.listScrollOffset(current,value,24,54));
        assertEquals(before,current);assertFalse(legacy.has("listScrollVersion"));
    }

    @Test void listScrollConversionRejectsInvalidMetricsAndUnvalidatedVersions(){
        var state=StudioPanelViewState.decode(encoded("{}")).orElseThrow();
        assertThrows(IllegalArgumentException.class,()->StudioPanelViewState.listScrollOffset(state,2,0,78));
        assertThrows(IllegalArgumentException.class,()->StudioPanelViewState.listScrollOffset(state,2,-1,78));
        assertThrows(IllegalArgumentException.class,()->StudioPanelViewState.listScrollOffset(state,2,22,-1));
        state.addProperty("listScrollVersion",2);var before=state.deepCopy();
        assertThrows(IllegalArgumentException.class,()->StudioPanelViewState.listScrollOffset(state,2,22,78));
        assertEquals(before,state);
    }
}
