/*
 * Copyright 2011-2016 Gregory Shrago
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.intellij.grammar.java;

import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.impl.FakePsiElement;
import com.intellij.psi.impl.source.resolve.reference.impl.providers.JavaClassReferenceProvider;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.ArrayUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.ContainerUtilRt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.EmptyVisitor;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * @author gregsh
 */
public abstract class JavaHelper {

  public enum MethodType { STATIC, INSTANCE, CONSTRUCTOR }

  public static JavaHelper getJavaHelper(@NotNull PsiElement context) {
    PsiFile file = context.getContainingFile();
    JavaHelper service = ServiceManager.getService(file.getProject(), JavaHelper.class);
    return service == null ? new AsmHelper() : service;
  }

  @Nullable
  public NavigatablePsiElement findClass(@Nullable String className) {
    return null;
  }

  @NotNull
  public List<NavigatablePsiElement> findClassMethods(@Nullable String className,
                                                      @NotNull MethodType methodType,
                                                      @Nullable String methodName,
                                                      int paramCount,
                                                      String... paramTypes) {
    return Collections.emptyList();
  }

  @Nullable
  public String getSuperClassName(@Nullable String className) {
    return null;
  }

  @NotNull
  public List<String> getMethodTypes(@Nullable NavigatablePsiElement method) {
    return Collections.emptyList();
  }

  @NotNull
  public String getDeclaringClass(@Nullable NavigatablePsiElement method) {
    return "";
  }

  @NotNull
  public List<String> getAnnotations(@Nullable NavigatablePsiElement element) {
    return Collections.emptyList();
  }

  @Nullable
  public PsiReferenceProvider getClassReferenceProvider() {
    return null;
  }

  @Nullable
  public NavigationItem findPackage(@Nullable String packageName) {
    return null;
  }


  private static boolean acceptsName(@Nullable String expected, @Nullable String actual) {
    return "*".equals(expected) || expected != null && expected.equals(actual);
  }

  private static boolean acceptsModifiers(int modifiers) {
    return !Modifier.isAbstract(modifiers) &&
           (Modifier.isPublic(modifiers) || !(Modifier.isPrivate(modifiers) || Modifier.isProtected(modifiers)));
  }

  private static class PsiHelper extends AsmHelper {
    private final JavaPsiFacade myFacade;
    private final PsiElementFactory myElementFactory;

    private PsiHelper(JavaPsiFacade facade, PsiElementFactory elementFactory) {
      myFacade = facade;
      myElementFactory = elementFactory;
    }

    @Override
    public PsiReferenceProvider getClassReferenceProvider() {
      JavaClassReferenceProvider provider = new JavaClassReferenceProvider();
      provider.setSoft(false);
      return provider;
    }

    @Override
    public NavigatablePsiElement findClass(String className) {
      PsiClass aClass = findClassSafe(className);
      return aClass != null ? aClass : super.findClass(className);
    }

    private PsiClass findClassSafe(String className) {
      if (className == null) return null;
      try {
        return myFacade.findClass(className, GlobalSearchScope.allScope(myFacade.getProject()));
      }
      catch (IndexNotReadyException e) {
        return null;
      }
    }

    @Override
    public NavigationItem findPackage(String packageName) {
      return myFacade.findPackage(packageName);
    }

    @NotNull
    @Override
    public List<NavigatablePsiElement> findClassMethods(@Nullable String className,
                                                        @NotNull MethodType methodType,
                                                        @Nullable String methodName,
                                                        int paramCount,
                                                        String... paramTypes) {
      if (methodName == null) return Collections.emptyList();
      PsiClass aClass = findClassSafe(className);
      if (aClass == null) return super.findClassMethods(className, methodType, methodName, paramCount, paramTypes);
      List<NavigatablePsiElement> result = ContainerUtil.newArrayList();
      PsiMethod[] methods = methodType == MethodType.CONSTRUCTOR ? aClass.getConstructors() : aClass.getMethods();
      for (PsiMethod method : methods) {
        if (!acceptsName(methodName, method.getName())) continue;
        if (!acceptsMethod(method, methodType == MethodType.STATIC)) continue;
        if (!acceptsMethod(myElementFactory, method, paramCount, paramTypes)) continue;
        result.add(method);
      }
      return result;
    }

    @Nullable
    @Override
    public String getSuperClassName(@Nullable String className) {
      PsiClass aClass = findClassSafe(className);
      PsiClass superClass = aClass != null ? aClass.getSuperClass() : null;
      return superClass != null ? superClass.getQualifiedName() : super.getSuperClassName(className);
    }

    private static boolean acceptsMethod(PsiElementFactory elementFactory,
                                         PsiMethod method,
                                         int paramCount,
                                         String... paramTypes) {
      PsiParameterList parameterList = method.getParameterList();
      if (paramCount >= 0 && paramCount != parameterList.getParametersCount()) return false;
      if (paramTypes.length == 0) return true;
      if (parameterList.getParametersCount() < paramTypes.length) return false;
      PsiParameter[] psiParameters = parameterList.getParameters();
      for (int i = 0; i < paramTypes.length; i++) {
        String paramType = paramTypes[i];
        PsiParameter parameter = psiParameters[i];
        PsiType psiType = parameter.getType();
        if (acceptsName(paramType, psiType.getCanonicalText())) continue;
        try {
          if (psiType.isAssignableFrom(elementFactory.createTypeFromText(paramType, parameter))) continue;
        }
        catch (IncorrectOperationException ignored) {
        }
        return false;
      }
      return true;
    }

    private static boolean acceptsMethod(PsiMethod method, boolean staticMethods) {
      PsiModifierList modifierList = method.getModifierList();
      return staticMethods == modifierList.hasModifierProperty(PsiModifier.STATIC) &&
             !modifierList.hasModifierProperty(PsiModifier.ABSTRACT) &&
             (modifierList.hasModifierProperty(PsiModifier.PUBLIC) ||
              !(modifierList.hasModifierProperty(PsiModifier.PROTECTED) ||
                modifierList.hasModifierProperty(PsiModifier.PRIVATE)));
    }

    @NotNull
    @Override
    public List<String> getMethodTypes(NavigatablePsiElement method) {
      if (!(method instanceof PsiMethod)) return super.getMethodTypes(method);
      PsiMethod psiMethod = (PsiMethod)method;
      PsiType returnType = psiMethod.getReturnType();
      List<String> strings = new ArrayList<String>();
      strings.add(returnType == null ? "" : returnType.getCanonicalText());
      for (PsiParameter parameter : psiMethod.getParameterList().getParameters()) {
        strings.add(parameter.getType().getCanonicalText());
        strings.add(parameter.getName());
      }
      return strings;
    }

    @NotNull
    @Override
    public String getDeclaringClass(@Nullable NavigatablePsiElement method) {
      if (!(method instanceof PsiMethod)) return super.getDeclaringClass(method);
      PsiMethod psiMethod = (PsiMethod)method;
      PsiClass aClass = psiMethod.getContainingClass();
      return aClass == null ? "" : StringUtil.notNullize(aClass.getQualifiedName());
    }

    @NotNull
    @Override
    public List<String> getAnnotations(NavigatablePsiElement element) {
      if (!(element instanceof PsiModifierListOwner)) return super.getAnnotations(element);
      PsiModifierList modifierList = ((PsiModifierListOwner)element).getModifierList();
      if (modifierList == null) return ContainerUtilRt.emptyList();
      List<String> strings = new ArrayList<String>();
      for (PsiAnnotation annotation : modifierList.getAnnotations()) {
        if (annotation.getParameterList().getAttributes().length > 0) continue;
        strings.add(annotation.getQualifiedName());
      }
      return strings;
    }
  }

  public static class ReflectionHelper extends JavaHelper {
    @Nullable
    @Override
    public NavigatablePsiElement findClass(String className) {
      Class<?> aClass = findClassSafe(className);
      return aClass == null ? null : new MyElement<Class>(aClass);
    }

    @Nullable
    private static Class<?> findClassSafe(String className) {
      if (className == null) return null;
      try {
        return Class.forName(className);
      }
      catch (Exception e) {
        return null;
      }
    }

    @NotNull
    @Override
    public List<NavigatablePsiElement> findClassMethods(@Nullable String className,
                                                        @NotNull MethodType methodType,
                                                        @Nullable String methodName,
                                                        int paramCount,
                                                        String... paramTypes) {
      Class<?> aClass = findClassSafe(className);
      if (aClass == null || methodName == null) return Collections.emptyList();
      List<NavigatablePsiElement> result = ContainerUtil.newArrayList();
      Member[] methods = methodType == MethodType.CONSTRUCTOR ? aClass.getDeclaredConstructors() : aClass.getDeclaredMethods();
      for (Member method : methods) {
        if (!acceptsName(methodName, method.getName())) continue;
        if (!acceptsMethod(method, methodType == MethodType.STATIC)) continue;
        if (!acceptsMethod(method, paramCount, paramTypes)) continue;
        result.add(new MyElement<Member>(method));
      }
      return result;
    }

    @Nullable
    @Override
    public String getSuperClassName(@Nullable String className) {
      Class<?> aClass = findClassSafe(className);
      Class<?> superClass = aClass == null ? null : aClass.getSuperclass();
      return superClass != null && superClass != Object.class ? superClass.getName() : null;
    }

    private static boolean acceptsMethod(Member method, int paramCount, String... paramTypes) {
      Class<?>[] parameterTypes = method instanceof Method? ((Method)method).getParameterTypes() :
                                  method instanceof Constructor ? ((Constructor)method).getParameterTypes() :
                                  ArrayUtil.EMPTY_CLASS_ARRAY;
      if (paramCount >= 0 && paramCount != parameterTypes.length) return false;
      if (paramTypes.length == 0) return true;
      if (paramTypes.length > parameterTypes.length) return false;
      for (int i = 0; i < paramTypes.length; i++) {
        String paramType = paramTypes[i];
        Class<?> parameter = parameterTypes[i];
        if (acceptsName(paramType, parameter.getCanonicalName())) continue;
        Class<?> paramClass = findClassSafe(paramType);
        if (paramClass != null && parameter.isAssignableFrom(paramClass)) continue;
        return false;
      }
      return true;
    }

    private static boolean acceptsMethod(Member method, boolean staticMethods) {
      int modifiers = method.getModifiers();
      return staticMethods == Modifier.isStatic(modifiers) && acceptsModifiers(modifiers);
    }

    @NotNull
    @Override
    public List<String> getMethodTypes(NavigatablePsiElement method) {
      if (method == null) return Collections.emptyList();
      Method delegate = ((MyElement<Method>)method).myDelegate;
      Type[] parameterTypes = delegate.getGenericParameterTypes();
      List<String> result = new ArrayList<String>(parameterTypes.length + 1);
      result.add(delegate.getGenericReturnType().toString());
      int paramCounter = 0;
      for (Type parameterType : parameterTypes) {
        result.add(parameterType.toString());
        result.add("p" + (paramCounter++));
      }
      return result;
    }

    @NotNull
    @Override
    public String getDeclaringClass(@Nullable NavigatablePsiElement method) {
      if (method == null) return "";
      return ((MyElement<Method>)method).myDelegate.getDeclaringClass().getName();
    }

    @NotNull
    @Override
    public List<String> getAnnotations(NavigatablePsiElement element) {
      if (element == null) return Collections.emptyList();
      AnnotatedElement delegate = ((MyElement<AnnotatedElement>)element).myDelegate;
      Annotation[] annotations = delegate.getDeclaredAnnotations();
      List<String> result = new ArrayList<String>(annotations.length);
      for (Annotation annotation : annotations) {
        Class<? extends Annotation> annotationType = annotation.annotationType(); // todo parameters?
        result.add(annotationType.getCanonicalName());
      }
      return result;
    }
  }

  public static class AsmHelper extends JavaHelper {
    @Nullable
    @Override
    public NavigatablePsiElement findClass(String className) {
      ClassInfo info = findClassSafe(className);
      return info == null ? null : new MyElement<ClassInfo>(info);
    }

    @NotNull
    @Override
    public List<NavigatablePsiElement> findClassMethods(@Nullable String className,
                                                        @NotNull MethodType methodType,
                                                        @Nullable final String methodName,
                                                        int paramCount,
                                                        String... paramTypes) {
      ClassInfo aClass = findClassSafe(className);
      if (aClass == null || methodName == null) return Collections.emptyList();
      List<NavigatablePsiElement> result = ContainerUtil.newArrayList();
      for (MethodInfo method : aClass.methods) {
        if (!acceptsName(methodName, method.name)) continue;
        if (!acceptsMethod(method, methodType)) continue;
        if (!acceptsMethod(method, paramCount, paramTypes)) continue;
        result.add(new MyElement<MethodInfo>(method));
      }
      return result;
    }

    @Nullable
    @Override
    public String getSuperClassName(@Nullable String className) {
      ClassInfo aClass = findClassSafe(className);
      return aClass == null ? null : aClass.superClass;
    }

    private static boolean acceptsMethod(MethodInfo method, int paramCount, String... paramTypes) {
      if (paramCount >= 0 && paramCount + 1 != method.types.size()) return false;
      if (paramTypes.length == 0) return true;
      if (paramTypes.length + 1 > method.types.size()) return false;
      for (int i = 0; i < paramTypes.length; i++) {
        String paramType = paramTypes[i];
        String parameter = method.types.get(i + 1);
        if (acceptsName(paramType, parameter)) continue;
        ClassInfo info = findClassSafe(paramType);
        if (info != null) {
          if (Comparing.equal(info.superClass, parameter)) continue;
          if (info.interfaces.contains(parameter)) continue;
        }
        return false;
      }
      return true;
    }

    private static boolean acceptsMethod(MethodInfo method, MethodType methodType) {
      return method.methodType == methodType && acceptsModifiers(method.modifiers);
    }

    @NotNull
    @Override
    public List<String> getMethodTypes(NavigatablePsiElement method) {
      if (method == null) return Collections.emptyList();
      MethodInfo signature = ((MyElement<MethodInfo>)method).myDelegate;
      return signature.types;
    }

    @NotNull
    @Override
    public String getDeclaringClass(@Nullable NavigatablePsiElement method) {
      if (method == null) return "";
      return ((MyElement<MethodInfo>)method).myDelegate.declaringClass;
    }

    @NotNull
    @Override
    public List<String> getAnnotations(NavigatablePsiElement element) {
      Object delegate = element == null ? null : ((MyElement<?>)element).myDelegate;
      if (delegate instanceof ClassInfo) return ((ClassInfo)delegate).annotations;
      if (delegate instanceof MethodInfo) return ((MethodInfo)delegate).annotations;
      return Collections.emptyList();
    }

    private static ClassInfo findClassSafe(String className) {
      if (className == null) return null;
      try {
        String resourceName = className.replace('.', '/') + ".class";
        InputStream is = JavaHelper.class.getClassLoader().getResourceAsStream(resourceName);
        if (is == null) return null;
        byte[] bytes = FileUtil.loadBytes(is);
        is.close();
        return getClassInfo(className, bytes);
      }
      catch (Exception e) {
        return null;
      }
    }

    private static ClassInfo getClassInfo(String className, byte[] bytes) {
      final ClassInfo info = new ClassInfo();
      info.name = className;
      new ClassReader(bytes).accept(new MyClassVisitor(info), 0);
      return info;
    }

    private static MethodInfo getMethodInfo(String className, String methodName, String signature) {
      final MethodInfo methodInfo = new MethodInfo();
      methodInfo.name = methodName;
      methodInfo.declaringClass = className;

      try {
        MySignatureVisitor visitor = new MySignatureVisitor(methodInfo);
        new SignatureReader(signature).accept(visitor);
        visitor.finishElement(null);
      }
      catch (Exception e) {
        System.err.println(
          e.getClass().getSimpleName() + " in parsing " + className + "." + methodName + "() signature: " + signature);
      }
      return methodInfo;
    }

    private static class MyClassVisitor extends EmptyVisitor {
      enum State {CLASS, METHOD, ANNO}

      private final ClassInfo myInfo;

      MyClassVisitor(ClassInfo info) {
        myInfo = info;
      }

      private State state;

      private MethodInfo methodInfo;
      private String annoDesc;
      private int annoParamCounter;

      public void visit(int version,
                        int access,
                        String name,
                        String signature,
                        String superName,
                        String[] interfaces) {
        state = State.CLASS;
        myInfo.superClass = fixClassName(superName);
        for (String s : interfaces) {
          myInfo.interfaces.add(fixClassName(s));
        }
      }

      @Override
      public void visitEnd() {
        if (state == State.METHOD) {
          state = State.CLASS;
          myInfo.methods.add(methodInfo);
          methodInfo = null;
        }
        else if (state == State.ANNO) {
          state = State.METHOD;
          if (annoParamCounter == 0) {
            methodInfo.annotations.add(fixClassName(annoDesc.substring(1, annoDesc.length() - 1)));
          }
          annoParamCounter = 0;
          annoDesc = null;
        }
      }

      @Override
      public MethodVisitor visitMethod(int access,
                                       String name,
                                       String desc,
                                       String signature,
                                       String[] exceptions) {
        state = State.METHOD;
        methodInfo = getMethodInfo(myInfo.name, name, ObjectUtils.chooseNotNull(signature, desc));
        methodInfo.modifiers = access;
        methodInfo.methodType = "<init>".equals(name)? MethodType.CONSTRUCTOR :
                                Modifier.isStatic(access) ? MethodType.STATIC :
                                MethodType.INSTANCE;
        return this; // visit annotations
      }

      @Override
      public AnnotationVisitor visitAnnotation(String s, boolean b) {
        if (state == State.METHOD) {
          state = State.ANNO;
          annoDesc = s;
          return this;
        }
        return null;
      }

      @Override
      public void visit(String s, Object o) {
        annoParamCounter++;
      }

      @Override
      public void visitEnum(String s, String s2, String s3) {
        annoParamCounter++;
      }

      @Override
      public AnnotationVisitor visitAnnotation(String s, String s2) {
        annoParamCounter++;
        return null;
      }

      @Override
      public AnnotationVisitor visitArray(String s) {
        annoParamCounter++;
        return null;
      }
    }

    private static String fixClassName(String s) {
      return s == null ? null : s.replace('/', '.');
    }

    private static class MySignatureVisitor implements SignatureVisitor {
      enum State {PARAM, RETURN, CLASS, ARRAY, GENERIC}

      private final MethodInfo myMethodInfo;
      private final LinkedList<State> states = new LinkedList<State>();

      private final StringBuilder myBuilder = new StringBuilder();

      MySignatureVisitor(MethodInfo methodInfo) {
        myMethodInfo = methodInfo;
      }

      @Override
      public void visitFormalTypeParameter(String s) {
      }

      @Override
      public SignatureVisitor visitClassBound() {
        return null;
      }

      @Override
      public SignatureVisitor visitInterfaceBound() {
        return this;
      }

      @Override
      public SignatureVisitor visitSuperclass() {
        return null;
      }

      @Override
      public SignatureVisitor visitInterface() {
        return null;
      }

      @Override
      public SignatureVisitor visitParameterType() {
        finishElement(null);
        states.push(State.PARAM);
        return this;
      }

      @Override
      public SignatureVisitor visitReturnType() {
        finishElement(null);
        states.push(State.RETURN);
        return this;
      }

      @Override
      public SignatureVisitor visitExceptionType() {
        return null;
      }

      @Override
      public void visitBaseType(char c) {
        myBuilder.append(org.objectweb.asm.Type.getType(String.valueOf(c)).getClassName());
      }

      @Override
      public void visitTypeVariable(String s) {
      }

      @Override
      public SignatureVisitor visitArrayType() {
        states.push(State.ARRAY);
        return this;
      }

      @Override
      public void visitClassType(String s) {
        states.push(State.CLASS);
        myBuilder.append(fixClassName(s));
      }

      @Override
      public void visitInnerClassType(String s) {
      }

      @Override
      public void visitTypeArgument() {
        states.push(State.GENERIC);
        myBuilder.append("<");
      }

      @Override
      public SignatureVisitor visitTypeArgument(char c) {
        if (states.peekFirst() == State.CLASS) {
          states.push(State.GENERIC);
          myBuilder.append("<");
        }
        else {
          finishElement(State.GENERIC);
          myBuilder.append(", ");
        }
        return this;
      }

      @Override
      public void visitEnd() {
        finishElement(State.CLASS);
        states.pop();
      }

      private void finishElement(State finishState) {
        if (myBuilder.length() == 0) return;
        main:
        while (!states.isEmpty()) {
          if (finishState == states.peekFirst()) break;
          State state = states.pop();
          switch (state) {
            case PARAM:
              myMethodInfo.types.add(myBuilder.toString());
              myMethodInfo.types.add("p" + (myMethodInfo.types.size() / 2));
              myBuilder.setLength(0);
              break main;
            case RETURN:
              myMethodInfo.types.add(0, myBuilder.toString());
              myBuilder.setLength(0);
              break main;
            case ARRAY:
              myBuilder.append("[]");
              break;
            case GENERIC:
              myBuilder.append(">");
              break;
            case CLASS:
              break;
          }
        }
      }
    }
  }

  private static class MyElement<T> extends FakePsiElement implements NavigatablePsiElement {

    private final T myDelegate;

    MyElement(T delegate) {
      myDelegate = delegate;
    }

    @Override
    public PsiElement getParent() {
      return null;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      MyElement element = (MyElement)o;

      if (!myDelegate.equals(element.myDelegate)) return false;

      return true;
    }

    @Override
    public int hashCode() {
      return myDelegate.hashCode();
    }
  }

  private static class ClassInfo {
    String name;
    String superClass;
    List<String> interfaces = ContainerUtil.newSmartList();
    List<String> annotations = ContainerUtil.newSmartList();
    List<MethodInfo> methods = ContainerUtil.newSmartList();
  }

  private static class MethodInfo {
    MethodType methodType;
    String name;
    String declaringClass;
    int modifiers;
    List<String> annotations = ContainerUtil.newSmartList();
    List<String> types = ContainerUtil.newSmartList();
  }

}
