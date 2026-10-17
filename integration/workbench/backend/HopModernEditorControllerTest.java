package com.ruoyi.web.controller.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.workbench.WorkbenchProperties;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import static org.junit.jupiter.api.Assertions.*;

class HopModernEditorControllerTest {
    @TempDir Path temp;
    private HopModernEditorController subject(){
        var config=new WorkbenchProperties();
        config.setHopProjectsPath(temp.toString());
        return new HopModernEditorController(config,new ObjectMapper());
    }
    private Path file() throws Exception {
        Path project=Files.createDirectories(temp.resolve("Demo/flows"));
        Path file=project.resolve("test.hpl");
        Files.writeString(file,"<pipeline><info><name>Test</name></info><order/></pipeline>");
        return file;
    }
    private Path resolved(HopModernEditorController controller,String name) throws Exception {
        Method method=HopModernEditorController.class.getDeclaredMethod(
            "requireProjectFile",String.class,String.class);
        method.setAccessible(true);
        return (Path)method.invoke(controller,"Demo",name);
    }
    @Test void validatesOnlyProjectRelativeHopPipelines() throws Exception {
        Path file=file();
        var subject=subject();
        assertEquals(file,resolved(subject,"flows/test.hpl"));
        for(String invalid:new String[]{"../test.hpl","/etc/passwd.hpl","flows/../test.hpl",
            "flows/x.hwf","flows/evil\\test.hpl","flows//test.hpl"}) {
            assertThrows(InvocationTargetException.class,()->resolved(subject,invalid),invalid);
        }
    }
    @Test void rejectsDirectorySymlinksBeforeReading() throws Exception {
        file();
        Files.createSymbolicLink(temp.resolve("Demo/escape"),temp);
        assertThrows(InvocationTargetException.class,()->
            resolved(subject(),"escape/Demo/flows/test.hpl"));
    }
    @Test void saveRejectsMissingRevisionBeforeCallingEngineOrChangingFiles() throws Exception {
        Path file=file();
        byte[] original=Files.readAllBytes(file);
        var request=new HopModernEditorController.SaveRequest(
            "Demo","flows/test.hpl","invalid",new ObjectMapper().createArrayNode(),
            new ObjectMapper().createArrayNode());
        assertEquals(HttpStatus.BAD_REQUEST,subject().save(request).getStatusCode());
        assertArrayEquals(original,Files.readAllBytes(file));
    }
    @Test void unconfiguredEngineCannotMakeOpenSucceed() throws Exception {
        file();
        assertEquals(HttpStatus.BAD_GATEWAY,
            subject().open(new HopModernEditorController.OpenRequest(
                "Demo","flows/test.hpl")).getStatusCode());
    }
    @Test void atomicSaveBacksUpBytesAndReplacesFileWithoutTruncation() throws Exception {
        Path file=file();
        byte[] original=Files.readAllBytes(file);
        var changed="<pipeline><info><name>Edited</name></info><order/></pipeline>";
        Method method=HopModernEditorController.class.getDeclaredMethod(
            "atomicSave",Path.class,byte[].class);
        method.setAccessible(true);
        method.invoke(subject(),file,changed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(changed,Files.readString(file));
        assertArrayEquals(original,Files.readAllBytes(
            file.resolveSibling(".test.hpl.anlan.bak")));
        assertTrue(Files.getPosixFilePermissions(file).contains(
            java.nio.file.attribute.PosixFilePermission.GROUP_WRITE));
    }
    @Test void readTransformConfigRequiresAuthorizedProjectAndRevision() throws Exception {
        file();
        var read=new HopModernEditorController.ConfigReadRequest(
            "Demo", "flows/test.hpl", "invalid",
            new ObjectMapper().createArrayNode(),new ObjectMapper().createArrayNode(),"json-input-1");
        assertEquals(HttpStatus.BAD_REQUEST,subject().configRead(read).getStatusCode());
        Method method=HopModernEditorController.class.getDeclaredMethod(
            "configRead",HopModernEditorController.ConfigReadRequest.class);
        assertTrue(method.getAnnotation(PreAuthorize.class).value().contains("workbench:hop:access"));
    }
    @Test void rejectsUnsupportedConfigEditsBeforeWriting() throws Exception {
        Path file=file();
        byte[] original=Files.readAllBytes(file);
        var request=new HopModernEditorController.SaveRequest(
            "Demo", "flows/test.hpl", "a".repeat(64),
            new ObjectMapper().createArrayNode(),new ObjectMapper().createArrayNode(),
            new ObjectMapper().createArrayNode());
        assertEquals(HttpStatus.BAD_REQUEST,subject().save(request).getStatusCode());
        assertArrayEquals(original,Files.readAllBytes(file));
    }
    @Test void openAndSaveHaveSeparateWorkbenchPermissions() throws Exception {
        Method open=HopModernEditorController.class.getDeclaredMethod(
            "open",HopModernEditorController.OpenRequest.class);
        Method save=HopModernEditorController.class.getDeclaredMethod(
            "save",HopModernEditorController.SaveRequest.class);
        assertTrue(open.getAnnotation(PreAuthorize.class).value().contains("workbench:hop:access"));
        assertTrue(save.getAnnotation(PreAuthorize.class).value().contains("workbench:hop:edit"));
    }
}