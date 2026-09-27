package mcpthespire.patches;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.LocalVariableAttribute;

public class PatchParameterMetadataTest {
    public static void main(String[] args) throws Exception {
        ClassPool pool = new ClassPool(true);
        pool.insertClassPath(args[0]);
        requireCapture(pool, "mcpthespire.patches.InputActionPatch$JustPressedPatch", "___keycode");
        requireCapture(pool, "mcpthespire.patches.InputActionPatch$PressedPatch", "___keycode");
        System.out.println("ModTheSpire patch parameter metadata present");
    }

    private static void requireCapture(ClassPool pool, String className, String parameterName) throws Exception {
        CtClass patchClass = pool.get(className);
        CtMethod method = patchClass.getDeclaredMethod("Prefix");
        CodeAttribute code = method.getMethodInfo2().getCodeAttribute();
        LocalVariableAttribute variables = (LocalVariableAttribute) code.getAttribute(LocalVariableAttribute.tag);
        if (variables != null) {
            for (int i = 0; i < variables.tableLength(); i++) {
                if (parameterName.equals(variables.variableName(i))) {
                    return;
                }
            }
        }
        throw new AssertionError(className + ".Prefix is missing LocalVariableTable entry " + parameterName);
    }
}
