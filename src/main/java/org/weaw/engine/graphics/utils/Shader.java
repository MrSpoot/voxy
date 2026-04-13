package org.weaw.engine.graphics.utils;

import lombok.Getter;
import org.joml.*;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weaw.engine.utils.FileReader;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.opengl.GL11.GL_TRUE;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30C.glUniform1ui;
import static org.lwjgl.opengl.GL32C.GL_GEOMETRY_SHADER;

public class Shader {

    private static final Logger LOGGER = LoggerFactory.getLogger(Shader.class);

    @Getter
    private final int program;
    private final int vertex;
    private final int fragment;
    private final int geometry;
    private final Map<String, Integer> uniforms = new HashMap<>();
    private String vertexCode;
    private String fragmentCode;
    private String geometryCode;

    public Shader(String path) {
        vertex = glCreateShader(GL_VERTEX_SHADER);
        fragment = glCreateShader(GL_FRAGMENT_SHADER);
        geometry = glCreateShader(GL_GEOMETRY_SHADER);
        program = glCreateProgram();

        loadShader(path);

        compileShader(vertex, vertexCode);
        compileShader(fragment, fragmentCode);
        if (geometryCode != null && !geometryCode.isEmpty()) {
            compileShader(geometry, geometryCode);
        }

        compileProgram(program);

        extractUniforms(vertexCode);
        extractUniforms(fragmentCode);
        if (geometryCode != null && !geometryCode.isEmpty()) {
            extractUniforms(geometryCode);
        }
    }

    private void compileProgram(int programId) {
        glAttachShader(programId, vertex);
        glAttachShader(programId, fragment);

        if(geometryCode != null && !geometryCode.isEmpty()) {
            glAttachShader(programId, geometry);
        }

        glLinkProgram(program);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        glDeleteShader(geometry);
        checkShaderProgramLinking(program);
    }

    private void compileShader(int shaderId, String source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("Shader source is empty");
        }
        glShaderSource(shaderId, source);
        glCompileShader(shaderId);

        checkShaderCompilation(shaderId);
    }

    private void checkShaderCompilation(int shader) {
        int compiled = glGetShaderi(shader, GL_COMPILE_STATUS);
        if (compiled != GL_TRUE) {
            throw new IllegalStateException("Shader compilation failed: " + glGetShaderInfoLog(shader));
        }
    }

    private void checkShaderProgramLinking(int shaderProgram) {
        int linked = glGetProgrami(shaderProgram, GL_LINK_STATUS);
        if (linked != GL_TRUE) {
            throw new IllegalStateException("Shader program linking failed: " + glGetProgramInfoLog(shaderProgram));
        }
    }

    // UNIFORM

    public void createUniform(String uniformName) {
        int uniformLocation = glGetUniformLocation(program, uniformName);
        if (uniformLocation >= 0) {
            uniforms.put(uniformName, uniformLocation);
        }else{
            LOGGER.warn("Uniform '{}' not found in shader '{}'", uniformName, program);
        }

    }

    public Integer getUniformLocation(String uniformName) {
        return uniforms.get(uniformName);
    }

    public void setUniform(String uniformName, int value) {
        Integer location = getUniformLocation(uniformName);
        if (location != null) {
            glUniform1i(location, value);
        }
    }

    public void setUniform(String uniformName, int value, boolean unsigned) {
        Integer location = getUniformLocation(uniformName);
        if (location != null) {
            if(unsigned) {
                glUniform1ui(location, value);
            }else{
                glUniform1i(location, value);
            }
        }
    }

    public void setUniform(String uniformName, Matrix4f value) {
        Integer location = getUniformLocation(uniformName);
        if (location != null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                glUniformMatrix4fv(location, false, value.get(stack.mallocFloat(16)));
            }
        }
    }

    public void setUniform(String uniformName, Matrix3f value) {
        Integer location = getUniformLocation(uniformName);
        if (location != null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                glUniformMatrix3fv(location, false, value.get(stack.mallocFloat(16)));
            }
        }
    }

    public void setUniform(String uniformName, Vector4f value) {
        Integer location = getUniformLocation(uniformName);
        if (location != null) {
            glUniform4f(location, value.x, value.y, value.z, value.w);
        }
    }

    public void setUniform(String uniformName, Vector3f value) {
        Integer location = getUniformLocation(uniformName);
        if (location != null) {
            glUniform3f(location, value.x, value.y, value.z);
        }
    }

    public void setUniform(String uniformName, Vector2f value) {
        Integer location = getUniformLocation(uniformName);
        if (location != null) {
            glUniform2f(location, value.x, value.y);
        }
    }

    public void setUniform(String uniformName, float value) {
        Integer location = getUniformLocation(uniformName);
        if (location != null) {
            glUniform1f(location, value);
        }
    }

    public void setUniform(String uniformName, float x, float y, float z) {
        Integer location = getUniformLocation(uniformName);
        if (location != null) {
            glUniform3f(location, x, y, z);
        }
    }

    public void useProgram() {
        glUseProgram(program);
    }

    public void unbind(){
        glUseProgram(0);
    }

    private void extractUniforms(String shaderCode) {
        // Pattern pour les structures
        Pattern structPattern = Pattern.compile("struct\\s+(\\w+)\\s*\\{([^}]+)\\};");
        Matcher structMatcher = structPattern.matcher(shaderCode);

        while (structMatcher.find()) {
            String structName = structMatcher.group(1);
            String structBody = structMatcher.group(2);

            // Extraire les champs de la structure
            Pattern fieldPattern = Pattern.compile("\\w+\\s+(\\w+)\\s*;");
            Matcher fieldMatcher = fieldPattern.matcher(structBody);

            while (fieldMatcher.find()) {
                String fieldName = fieldMatcher.group(1);

                // Trouver les instances de la structure déclarées comme uniforms
                Pattern instancePattern = Pattern.compile("\\buniform\\s+" + structName + "\\s+(\\w+)\\s*;");
                Matcher instanceMatcher = instancePattern.matcher(shaderCode);

                while (instanceMatcher.find()) {
                    String instanceName = instanceMatcher.group(1);
                    String uniformName = instanceName + "." + fieldName;
                    LOGGER.debug("Create uniform [" + uniformName + "] for shader [" + program + "]");
                    createUniform(uniformName);
                }
            }
        }

        Pattern uniformPattern = Pattern.compile("\\buniform\\s+\\w+\\s+(\\w+(?:\\[\\d+\\])?)\\s*;");
        Matcher uniformMatcher = uniformPattern.matcher(shaderCode);

        while (uniformMatcher.find()) {
            String uniformName = uniformMatcher.group(1);

            // Check if this is an array uniform (e.g., "uShadowMaps[3]")
            if (uniformName.contains("[")) {
                // Extract base name and array size
                int bracketIndex = uniformName.indexOf('[');
                String baseName = uniformName.substring(0, bracketIndex);
                String sizeStr = uniformName.substring(bracketIndex + 1, uniformName.indexOf(']'));
                int arraySize = Integer.parseInt(sizeStr);

                // Create uniform for each array element
                for (int i = 0; i < arraySize; i++) {
                    String elementName = baseName + "[" + i + "]";
                    LOGGER.debug("Create array uniform [" + elementName + "] for shader [" + program + "]");
                    createUniform(elementName);
                }
            } else {
                // Regular uniform
                LOGGER.debug("Create uniform [" + uniformName + "] for shader [" + program + "]");
                createUniform(uniformName);
            }
        }
    }

    private void loadShader(String path){
        LOGGER.info("Load shader - ["+path+"]");
        String file = FileReader.readFile(path);

        Pattern vertexPattern = Pattern.compile("//@vs(.*?)//@endvs", Pattern.DOTALL);
        Matcher vertexMatcher = vertexPattern.matcher(file);

        StringBuilder vertex = new StringBuilder();

        while (vertexMatcher.find()){
            vertex.append(vertexMatcher.group(1)).append(" ");
        }

        Pattern fragmentPattern = Pattern.compile("//@fs(.*?)//@endfs", Pattern.DOTALL);
        Matcher fragmentMatcher = fragmentPattern.matcher(file);

        StringBuilder fragment = new StringBuilder();

        while (fragmentMatcher.find()){
            fragment.append(fragmentMatcher.group(1)).append(" ");
        }

        Pattern geometryPattern = Pattern.compile("//@gs(.*?)//@endgs", Pattern.DOTALL);
        Matcher geometryMatcher = geometryPattern.matcher(file);

        StringBuilder geometry = new StringBuilder();

        while (geometryMatcher.find()){
            geometry.append(geometryMatcher.group(1)).append(" ");
        }

        if(vertex.isEmpty() || fragment.isEmpty()){
            throw new IllegalArgumentException("Shader file must contain vertex and fragment sections: " + path);
        }

        this.vertexCode = vertex.toString().trim();
        this.fragmentCode = fragment.toString().trim();
        this.geometryCode = geometry.toString().trim();
    }

    public void destroy() {
        glDeleteProgram(program);
    }

    public void cleanup() {
        this.destroy();
    }


}
