package org.jetbrains.plugins.innerbuilder;

import com.intellij.codeInsight.generation.PsiFieldMember;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.jetbrains.plugins.innerbuilder.InnerBuilderUtils.areTypesPresentableEqual;

public class InnerBuilderGenerator implements Runnable {

    @NonNls
    private static final String BUILDER_CLASS_NAME = "Builder";
    @NonNls
    private static final String BUILDER_SETTER_DEFAULT_PARAMETER_NAME = "val";
    @NonNls
    private static final String BUILDER_SETTER_ALTERNATIVE_PARAMETER_NAME = "value";
    @NonNls
    private static final String JSR305_NONNULL = "javax.annotation.Nonnull";

    private final Project project;
    private final PsiFile file;
    private final Editor editor;
    private final List<PsiFieldMember> selectedFields;
    private final PsiElementFactory psiElementFactory;

    public static void generate(final Project project, final Editor editor, final PsiFile file,
                                final List<PsiFieldMember> selectedFields) {
        final Runnable builderGenerator = new InnerBuilderGenerator(project, file, editor, selectedFields);
        ApplicationManager.getApplication().runWriteAction(builderGenerator);
    }

    private InnerBuilderGenerator(final Project project, final PsiFile file, final Editor editor,
                                  final List<PsiFieldMember> selectedFields) {
        this.project = project;
        this.file = file;
        this.editor = editor;
        this.selectedFields = selectedFields;
        psiElementFactory = JavaPsiFacade.getInstance(project).getElementFactory();
    }

    @Override
    public void run() {
        final PsiClass targetClass = InnerBuilderUtils.getStaticOrTopLevelClass(file, editor);
        if (targetClass == null) {
            return;
        }
        final Set<InnerBuilderOption> options = currentOptions();
        final PsiClass builderClass = findOrCreateBuilderClass(targetClass, selectedFields);
        final PsiType builderType = psiElementFactory.createTypeFromText(BUILDER_CLASS_NAME, null);
        if (options.contains(InnerBuilderOption.JSON_CONSTRUCTOR)) {
            generateJsonConstructor(selectedFields, targetClass);
        }

        final PsiMethod constructor = generateConstructor(targetClass, builderType);

        addMethod(targetClass, null, constructor, true);
        final Collection<PsiFieldMember> finalFields = new ArrayList<>();
        final Collection<PsiFieldMember> nonFinalFields = new ArrayList<>();

        PsiElement lastAddedField = null;
        for (final PsiFieldMember fieldMember : selectedFields) {
            lastAddedField = findOrCreateField(builderClass, fieldMember, lastAddedField);
            if (fieldMember.getElement().hasModifierProperty(PsiModifier.FINAL)
                    && !options.contains(InnerBuilderOption.FINAL_SETTERS)) {
                finalFields.add(fieldMember);
                PsiUtil.setModifierProperty((PsiField) lastAddedField, PsiModifier.FINAL, true);
            } else {
                nonFinalFields.add(fieldMember);
            }
        }
        if (options.contains(InnerBuilderOption.NEW_BUILDER_METHOD)) {
            final PsiMethod newBuilderMethod = generateNewBuilderMethod(decapitalize(targetClass.getName()),
                                                                        builderType, finalFields, options);
            addMethod(builderClass, null, newBuilderMethod, false);
        }

        // builder constructor, accepting the final fields
        final PsiMethod builderConstructorMethod = generateBuilderConstructor(builderClass, finalFields, options);
        addMethod(builderClass, null, builderConstructorMethod, false);

        // builder copy constructor or static copy method
        if (options.contains(InnerBuilderOption.COPY_CONSTRUCTOR)) {
            if (options.contains(InnerBuilderOption.NEW_BUILDER_METHOD)) {
                final PsiMethod copyBuilderMethod = generateCopyBuilderMethod(
                        targetClass, builderType,
                        nonFinalFields, options);
                addMethod(targetClass, null, copyBuilderMethod, true);
            } else {
                final PsiMethod copyConstructorBuilderMethod = generateCopyConstructor(
                        targetClass, builderType,
                        selectedFields, options);
                addMethod(builderClass, null, copyConstructorBuilderMethod, true);
            }
        }

        // builder methods
        PsiElement lastAddedElement = null;
        for (final PsiFieldMember member : nonFinalFields) {
            final PsiMethod setterMethod = generateBuilderSetter(builderType, member, options);
            lastAddedElement = addMethod(builderClass, lastAddedElement, setterMethod, false);
        }

        // builder.build() method
        final PsiMethod buildMethod = generateBuildMethod(targetClass, options);
        addMethod(builderClass, lastAddedElement, buildMethod, false);

        JavaCodeStyleManager.getInstance(project).shortenClassReferences(file);
        CodeStyleManager.getInstance(project).reformat(builderClass);
    }

    private PsiMethod generateCopyBuilderMethod(final PsiClass targetClass, final PsiType builderType,
                                                final Collection<PsiFieldMember> fields,
                                                final Set<InnerBuilderOption> options) {
        final PsiMethod copyBuilderMethod = psiElementFactory.createMethod("builder", builderType);
        PsiUtil.setModifierProperty(copyBuilderMethod, PsiModifier.STATIC, true);
        PsiUtil.setModifierProperty(copyBuilderMethod, PsiModifier.PUBLIC, true);

        final PsiType targetClassType = psiElementFactory.createType(targetClass);
        final PsiParameter parameter = psiElementFactory.createParameter("copy", targetClassType);
        final PsiModifierList parameterModifierList = parameter.getModifierList();

        if (parameterModifierList != null) {
            if (options.contains(InnerBuilderOption.JSR305_ANNOTATIONS)) {
                parameterModifierList.addAnnotation(JSR305_NONNULL);
            }
        }
        copyBuilderMethod.getParameterList().add(parameter);
        final PsiCodeBlock copyBuilderBody = copyBuilderMethod.getBody();
        if (copyBuilderBody != null) {
            final StringBuilder copyBuilderParameters = new StringBuilder();
            for (final PsiFieldMember fieldMember : selectedFields) {
                if (fieldMember.getElement().hasModifierProperty(PsiModifier.FINAL)
                        && !options.contains(InnerBuilderOption.FINAL_SETTERS)) {

                    if (copyBuilderParameters.length() > 0) {
                        copyBuilderParameters.append(", ");
                    }

                    copyBuilderParameters.append(String.format("copy.%s", fieldMember.getElement().getName()));
                }
            }
            if (options.contains(InnerBuilderOption.NEW_BUILDER_METHOD)) {
                final PsiStatement newBuilderStatement = psiElementFactory.createStatementFromText(
                        String.format("%s builder = new %s(%s);", builderType.getPresentableText(),
                                      builderType.getPresentableText(), copyBuilderParameters),
                        copyBuilderMethod);
                copyBuilderBody.add(newBuilderStatement);

                addCopyBody(fields, copyBuilderMethod, "builder.");
                copyBuilderBody.add(psiElementFactory.createStatementFromText("return builder;", copyBuilderMethod));
            } else {
                final PsiStatement newBuilderStatement = psiElementFactory.createStatementFromText(
                        String.format("return new %s(%s);", builderType.getPresentableText(), copyBuilderParameters),
                        copyBuilderMethod);
                copyBuilderBody.add(newBuilderStatement);
            }
        }
        return copyBuilderMethod;
    }

    private PsiMethod generateCopyConstructor(final PsiClass targetClass, final PsiType builderType,
                                              final Collection<PsiFieldMember> nonFinalFields,
                                              final Set<InnerBuilderOption> options) {

        final PsiMethod copyConstructor = psiElementFactory.createConstructor(builderType.getPresentableText());
        PsiUtil.setModifierProperty(copyConstructor, PsiModifier.PUBLIC, true);

        final PsiType targetClassType = psiElementFactory.createType(targetClass);
        final PsiParameter constructorParameter = psiElementFactory.createParameter("copy", targetClassType);
        final PsiModifierList parameterModifierList = constructorParameter.getModifierList();

        if (parameterModifierList != null) {
            if (options.contains(InnerBuilderOption.JSR305_ANNOTATIONS))
                parameterModifierList.addAnnotation(JSR305_NONNULL);
        }
        copyConstructor.getParameterList().add(constructorParameter);
        addCopyBody(nonFinalFields, copyConstructor, "this.");
        return copyConstructor;
    }

    private void addCopyBody(final Collection<PsiFieldMember> fields, final PsiMethod method, final String qName) {
        final PsiCodeBlock methodBody = method.getBody();
        if (methodBody == null) {
            return;
        }
        for (final PsiFieldMember member : fields) {
            final PsiField field = member.getElement();
            final PsiStatement assignStatement = psiElementFactory.createStatementFromText(String.format(
                    "%s%2$s = copy.%3$s;", qName, field.getName(), field.getName()), method);
            methodBody.add(assignStatement);
        }
    }

    private PsiMethod generateBuilderConstructor(final PsiClass builderClass,
                                                 final Collection<PsiFieldMember> finalFields,
                                                 final Set<InnerBuilderOption> options) {

        final PsiMethod builderConstructor = psiElementFactory.createConstructor(builderClass.getName());
        if (options.contains(InnerBuilderOption.NEW_BUILDER_METHOD)) {
            PsiUtil.setModifierProperty(builderConstructor, PsiModifier.PRIVATE, true);
        } else {
            PsiUtil.setModifierProperty(builderConstructor, PsiModifier.PUBLIC, true);
        }
        final PsiCodeBlock builderConstructorBody = builderConstructor.getBody();
        if (builderConstructorBody != null) {
            for (final PsiFieldMember member : finalFields) {
                final PsiField field = member.getElement();
                final PsiType fieldType = field.getType();
                final String fieldName = field.getName();

                final PsiParameter parameter = psiElementFactory.createParameter(fieldName, fieldType);
                final PsiModifierList parameterModifierList = parameter.getModifierList();
                final boolean useJsr305 = options.contains(InnerBuilderOption.JSR305_ANNOTATIONS);

                if (!InnerBuilderUtils.isPrimitive(field) && parameterModifierList != null) {
                    if (useJsr305) parameterModifierList.addAnnotation(JSR305_NONNULL);
                }

                builderConstructor.getParameterList().add(parameter);
                final PsiStatement assignStatement = psiElementFactory.createStatementFromText(String.format(
                        "this.%1$s = %1$s;", fieldName), builderConstructor);
                builderConstructorBody.add(assignStatement);
            }
        }

        return builderConstructor;
    }


    private PsiMethod generateNewBuilderMethod(
            String builderName,
            final PsiType builderType,
            final Collection<PsiFieldMember> finalFields,
            final Set<InnerBuilderOption> options) {
        final PsiMethod newBuilderMethod = psiElementFactory.createMethod(builderName, builderType);
        PsiUtil.setModifierProperty(newBuilderMethod, PsiModifier.STATIC, true);
        PsiUtil.setModifierProperty(newBuilderMethod, PsiModifier.PUBLIC, true);

        final StringBuilder fieldList = new StringBuilder();
        if (!finalFields.isEmpty()) {
            for (final PsiFieldMember member : finalFields) {
                final PsiField field = member.getElement();
                final PsiType fieldType = field.getType();
                final String fieldName = field.getName();

                final PsiParameter parameter = psiElementFactory.createParameter(fieldName, fieldType);
                final PsiModifierList parameterModifierList = parameter.getModifierList();
                if (parameterModifierList != null) {
                    if (!InnerBuilderUtils.isPrimitive(field)) {
                        if (options.contains(InnerBuilderOption.JSR305_ANNOTATIONS))
                            parameterModifierList.addAnnotation(JSR305_NONNULL);
                    }
                }
                newBuilderMethod.getParameterList().add(parameter);
                if (fieldList.length() > 0) {
                    fieldList.append(", ");
                }
                fieldList.append(fieldName);
            }
        }
        final PsiCodeBlock newBuilderMethodBody = newBuilderMethod.getBody();
        if (newBuilderMethodBody != null) {
            final PsiStatement newStatement = psiElementFactory.createStatementFromText(
                    String.format("return new %s();", builderType.getPresentableText()),
                    newBuilderMethod);
            newBuilderMethodBody.add(newStatement);
        }
        return newBuilderMethod;
    }

    private PsiMethod generateBuilderSetter(final PsiType builderType, final PsiFieldMember member,
                                            final Set<InnerBuilderOption> options) {

        final PsiField field = member.getElement();
        final PsiType fieldType = field.getType();
        final String fieldName = InnerBuilderUtils.hasOneLetterPrefix(field.getName()) ?
                Character.toLowerCase(field.getName().charAt(1)) + field.getName().substring(2) : field.getName();

        final String methodName;
        if (options.contains(InnerBuilderOption.WITH_NOTATION)) {
            methodName = String.format("with%s", InnerBuilderUtils.capitalize(fieldName));
        } else if (options.contains(InnerBuilderOption.SET_NOTATION)) {
            methodName = String.format("set%s", InnerBuilderUtils.capitalize(fieldName));
        } else {
            methodName = fieldName;
        }

        final String parameterName = fieldName;
        final PsiMethod setterMethod = psiElementFactory.createMethod(methodName, builderType);
        final boolean useJsr305 = options.contains(InnerBuilderOption.JSR305_ANNOTATIONS);

        if (useJsr305) setterMethod.getModifierList().addAnnotation(JSR305_NONNULL);

        setterMethod.getModifierList().setModifierProperty(PsiModifier.PUBLIC, true);
        final PsiParameter setterParameter = psiElementFactory.createParameter(parameterName, getType(field));

        if (!(fieldType instanceof PsiPrimitiveType)) {
            final PsiModifierList setterParameterModifierList = setterParameter.getModifierList();
            if (setterParameterModifierList != null) {
                if (useJsr305) setterParameterModifierList.addAnnotation(JSR305_NONNULL);
            }
        }
        setterMethod.getParameterList().add(setterParameter);
        final PsiCodeBlock setterMethodBody = setterMethod.getBody();
        if (setterMethodBody != null) {
            final String actualFieldName = options.contains(InnerBuilderOption.FIELD_NAMES) ?
                    "this." + fieldName :
                    fieldName;
            final String assignText;
            if (field.getType().getCanonicalText().contains("Optional")) {
                assignText = String.format("this.%s = Optional.ofNullable(%s);", actualFieldName, parameterName);
            } else {
                assignText = String.format("this.%s = %s;", actualFieldName, parameterName);
            }
            final PsiStatement assignStatement = psiElementFactory.createStatementFromText(assignText, setterMethod);
            setterMethodBody.add(assignStatement);
            setterMethodBody.add(InnerBuilderUtils.createReturnThis(psiElementFactory, setterMethod));
        }
        setSetterComment(setterMethod, fieldName, parameterName);
        return setterMethod;
    }

    private static PsiType getType(PsiField fieldMember) {
        if (fieldMember.getType().getCanonicalText().contains("Optional")) {
            return ((PsiClassType) fieldMember.getType()).getParameters()[0];
        }
        return fieldMember.getType();
    }

    private void generateJsonConstructor(List<PsiFieldMember> fields, final PsiClass targetClass) {
        final PsiMethod constructor = psiElementFactory.createConstructor(targetClass.getName());
        constructor.getModifierList().setModifierProperty(PsiModifier.PUBLIC, true);

        final var oldConstructors = targetClass.findMethodsByName(targetClass.getName(), false);
        PsiMethod oldConstructor = null;

        for (PsiMethod psiMethod : oldConstructors) {
            final var parametrs = psiMethod.getParameterList().getParameters();
            if (parametrs.length > 1 || parametrs.length == 1 && !parametrs[0].getName().equals("builder")) {
                oldConstructor = psiMethod;
                break;
            }
        }
        Map<String, String> oldNamesForParams = new HashMap<>();

        if (oldConstructor == null) {
            firstJsonConstructorCreation(fields, constructor);
        } else {
            for (PsiParameter parameter : oldConstructor.getParameterList().getParameters()) {
                final var anotations = parameter.getAnnotations();
                if (anotations.length == 1) {
                    final var anotationValue = anotations[0].findAttributeValue("value").getText().replace("\"", "");
                    oldNamesForParams.put(snakeCaseToСamelCase(anotationValue), anotationValue);
                }
            }

            for (PsiFieldMember fieldMember : fields) {
                final var psiField = fieldMember.getElement();
                String fieldName = psiField.getName();
                String originalName = oldNamesForParams.getOrDefault(fieldName, fieldName);

                final PsiParameter builderParameter = psiElementFactory.createParameter(fieldName, getType(psiField));

                psiField.setName(fieldName);
                builderParameter.getModifierList().addAnnotation(
                        String.format("com.fasterxml.jackson.annotation.JsonProperty(\"%s\")", originalName));

                constructor.getParameterList().add(builderParameter);
            }
        }

        final PsiCodeBlock constructorBody = constructor.getBody();
        if (constructorBody != null) {
            for (final PsiFieldMember member : selectedFields) {
                final PsiField field = member.getElement();

                final String fieldName = field.getName();

                final String assignText;
                if (field.getType().getCanonicalText().contains("Optional")) {
                    assignText = String.format("this.%1$s = Optional.ofNullable(%1$s);", fieldName);
                } else {
                    assignText = String.format("this.%1$s = %1$s;", fieldName);
                }

                final PsiStatement assignStatement = psiElementFactory.createStatementFromText(assignText, null);
                constructorBody.add(assignStatement);
            }
        }

        if (oldConstructor != null) {
            oldConstructor.replace(constructor);
        } else {
            targetClass.add(constructor);
        }
    }

    private void firstJsonConstructorCreation(List<PsiFieldMember> fields, PsiMethod constructor) {
        for (PsiFieldMember fieldMember : fields) {
            String originalName = fieldMember.getElement().getName();
            String fieldName = snakeCaseToСamelCase(originalName);


            final PsiParameter builderParameter = psiElementFactory.createParameter(fieldName,
                                                                                    getType(fieldMember.getElement()));
            fieldMember.getElement().setName(fieldName);
            builderParameter.getModifierList().addAnnotation(
                    String.format("com.fasterxml.jackson.annotation.JsonProperty(\"%s\")", originalName));
            constructor.getParameterList().add(builderParameter);
        }
    }


    private PsiMethod generateConstructor(final PsiClass targetClass, final PsiType builderType) {
        final PsiMethod constructor = psiElementFactory.createConstructor(targetClass.getName());
        constructor.getModifierList().setModifierProperty(PsiModifier.PRIVATE, true);

        final PsiParameter builderParameter = psiElementFactory.createParameter("builder", builderType);
        constructor.getParameterList().add(builderParameter);

        final PsiCodeBlock constructorBody = constructor.getBody();
        if (constructorBody != null) {
            for (final PsiFieldMember member : selectedFields) {
                final PsiField field = member.getElement();

                final String fieldName = field.getName();

                final String assignText = String.format("this.%1$s = builder.%1$s;", fieldName);

                final PsiStatement assignStatement = psiElementFactory.createStatementFromText(assignText, null);
                constructorBody.add(assignStatement);
            }
        }

        return constructor;
    }

    private PsiMethod generateBuildMethod(final PsiClass targetClass, final Set<InnerBuilderOption> options) {
        final PsiType targetClassType = psiElementFactory.createType(targetClass);
        final PsiMethod buildMethod = psiElementFactory.createMethod("build", targetClassType);

        final boolean useJsr305 = options.contains(InnerBuilderOption.JSR305_ANNOTATIONS);
        if (useJsr305)
            buildMethod.getModifierList().addAnnotation(JSR305_NONNULL);

        buildMethod.getModifierList().setModifierProperty(PsiModifier.PUBLIC, true);

        final PsiCodeBlock buildMethodBody = buildMethod.getBody();
        if (buildMethodBody != null) {
            final PsiStatement returnStatement = psiElementFactory.createStatementFromText(String.format(
                    "return new %s(this);", targetClass.getName()), buildMethod);
            buildMethodBody.add(returnStatement);
        }
        setBuildMethodComment(buildMethod, targetClass);
        return buildMethod;
    }

    @NotNull
    private PsiClass findOrCreateBuilderClass(final PsiClass targetClass, List<PsiFieldMember> fields) {
        final PsiClass builderClass = targetClass.findInnerClassByName(BUILDER_CLASS_NAME, false);
        if (builderClass != null) {
            return builderClass;
        }
        return createBuilderClass(targetClass, fields);
    }

    @NotNull
    private PsiClass createBuilderClass(final PsiClass targetClass, List<PsiFieldMember> selectedFields) {
        final PsiClass builderClass = (PsiClass) targetClass.add(psiElementFactory.createClass(BUILDER_CLASS_NAME));
        PsiUtil.setModifierProperty(builderClass, PsiModifier.STATIC, true);
        PsiUtil.setModifierProperty(builderClass, PsiModifier.FINAL, true);
        setBuilderComment(builderClass, targetClass);
        setBuilderAnnotation(builderClass);

        for (final PsiFieldMember fieldMember : selectedFields) {
            final var field = fieldMember.getElement();
            final var fieldName = field.getName();

            if (field.getType().getCanonicalText().contains("Optional")) {
                final var initialization = String.format("%1$s %2$s = Optional.empty();",
                                                         field.getTypeElement().getText(),
                                                         snakeCaseToСamelCase(fieldName));
                final var assignStatement = psiElementFactory.createFieldFromText(
                        initialization,
                        null);
                builderClass.add(assignStatement);
            }
        }
        return builderClass;
    }

    private PsiElement findOrCreateField(final PsiClass builderClass, final PsiFieldMember member,
                                         @Nullable final PsiElement last) {
        final PsiField field = member.getElement();
        final String fieldName = field.getName();
        final PsiType fieldType = field.getType();
        final PsiField existingField = builderClass.findFieldByName(fieldName, false);
        if (existingField == null || !areTypesPresentableEqual(existingField.getType(), fieldType)) {
            if (existingField != null) {
                existingField.delete();
            }
            if (!fieldType.getCanonicalText().contains("Optional")) {
                final PsiField newField = psiElementFactory.createField(fieldName, fieldType);
                if (last != null) {
                    return builderClass.addAfter(newField, last);
                } else {
                    return builderClass.add(newField);
                }
            }
        }
        return existingField;
    }

    private PsiElement addMethod(@NotNull final PsiClass target, @Nullable final PsiElement after,
                                 @NotNull final PsiMethod newMethod, final boolean replace) {
        PsiMethod existingMethod = target.findMethodBySignature(newMethod, false);
        if (existingMethod == null && newMethod.isConstructor()) {
            for (final PsiMethod constructor : target.getConstructors()) {
                if (InnerBuilderUtils.areParameterListsEqual(constructor.getParameterList(),
                                                             newMethod.getParameterList())) {
                    existingMethod = constructor;
                    break;
                }
            }
        }
        if (existingMethod == null) {
            if (after != null) {
                return target.addAfter(newMethod, after);
            } else {
                return target.add(newMethod);
            }
        } else if (replace) {
            existingMethod.replace(newMethod);
        }
        return existingMethod;
    }

    private static EnumSet<InnerBuilderOption> currentOptions() {
        final EnumSet<InnerBuilderOption> options = EnumSet.noneOf(InnerBuilderOption.class);
        final PropertiesComponent propertiesComponent = PropertiesComponent.getInstance();
        for (final InnerBuilderOption option : InnerBuilderOption.values()) {
            final boolean currentSetting = propertiesComponent.getBoolean(option.getProperty(), false);
            if (currentSetting) {
                options.add(option);
            }
        }
        return options;
    }

    private void setBuilderComment(final PsiClass clazz, final PsiClass targetClass) {
        if (currentOptions().contains(InnerBuilderOption.WITH_JAVADOC)) {
            StringBuilder str = new StringBuilder("/**\n").append("* {@code ");
            str.append(targetClass.getName()).append("} builder static inner class.\n");
            str.append("*/");
            setStringComment(clazz, str.toString());
        }
    }

    private void setBuilderAnnotation(final PsiClass clazz) {
        if (currentOptions().contains(InnerBuilderOption.PMD_AVOID_FIELD_NAME_MATCHING_METHOD_NAME_ANNOTATION)) {
            clazz.getModifierList().addAnnotation("SuppressWarnings(\"PMD.AvoidFieldNameMatchingMethodName\")");
        }
    }

    private void setSetterComment(final PsiMethod method, final String fieldName, final String parameterName) {
        if (currentOptions().contains(InnerBuilderOption.WITH_JAVADOC)) {
            StringBuilder str = new StringBuilder("/**\n").append("* Sets the {@code ").append(fieldName);
            str.append("} and returns a reference to this Builder enabling method chaining.\n");
            str.append("* @param ").append(parameterName).append(" the {@code ");
            str.append(fieldName).append("} to set\n");
            str.append("* @return a reference to this Builder\n*/");
            setStringComment(method, str.toString());
        }
    }

    private void setBuildMethodComment(final PsiMethod method, final PsiClass targetClass) {
        if (currentOptions().contains(InnerBuilderOption.WITH_JAVADOC)) {
            StringBuilder str = new StringBuilder("/**\n");
            str.append("* Returns a {@code ").append(targetClass.getName()).append("} built ");
            str.append("from the parameters previously set.\n*\n");
            str.append("* @return a {@code ").append(targetClass.getName()).append("} ");
            str.append("built with parameters of this {@code ").append(targetClass.getName()).append(".Builder}\n*/");
            setStringComment(method, str.toString());
        }
    }

    private void setStringComment(final PsiMethod method, final String strComment) {
        PsiComment comment = psiElementFactory.createCommentFromText(strComment, null);
        PsiDocComment doc = method.getDocComment();
        if (doc != null) {
            doc.replace(comment);
        } else {
            method.addBefore(comment, method.getFirstChild());
        }
    }

    private void setStringComment(final PsiClass clazz, final String strComment) {
        PsiComment comment = psiElementFactory.createCommentFromText(strComment, null);
        PsiDocComment doc = clazz.getDocComment();
        if (doc != null) {
            doc.replace(comment);
        } else {
            clazz.addBefore(comment, clazz.getFirstChild());
        }
    }

    public static String decapitalize(String string) {
        if (string == null || string.length() == 0) {
            return string;
        }

        char chars[] = string.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);

        return new String(chars);
    }

    public static String snakeCaseToСamelCase(String string) {
        if (string == null || !string.contains("_")) {
            return string;
        }

        char[] chars = string.toCharArray();
        int shift = 0;
        int currentWrite = 0;
        while (currentWrite + shift < chars.length) {
            if (chars[currentWrite + shift] == '_') {
                ++shift;
                chars[currentWrite] = Character.toUpperCase(chars[currentWrite + shift]);
            } else {
                chars[currentWrite] = chars[currentWrite + shift];
            }
            ++currentWrite;
        }

        return new String(chars, 0, currentWrite);
    }
}
