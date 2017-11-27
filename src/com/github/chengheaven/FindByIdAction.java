package com.github.chengheaven;

import com.google.common.base.CaseFormat;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.StdFileTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.search.EverythingGlobalScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.ui.awt.RelativePoint;
import org.apache.http.util.TextUtils;

import java.util.ArrayList;
import java.util.List;

import static com.github.chengheaven.EventLogger.log;

public class FindByIdAction extends AnAction {

    private static JBPopupFactory mFactory;
    private static StatusBar mStatusBar;
    private static PsiShortNamesCache mNamesCache;
    private PsiDirectory mResDir;
    private boolean isActivity = false;

    private static JBPopupFactory newInstance() {
        if (mFactory == null) {
            mFactory = JBPopupFactory.getInstance();
        }
        return mFactory;
    }

    @Override
    public void update(AnActionEvent event) {
        super.update(event);
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        Project project = event.getData(CommonDataKeys.PROJECT);
        PsiFile psiFile = event.getData(CommonDataKeys.PSI_FILE);
        mFactory = newInstance();
        mStatusBar = WindowManager.getInstance().getStatusBar(project);
        Presentation presentation = event.getPresentation();
        if (editor != null && project != null && psiFile instanceof PsiJavaFile) {
            mNamesCache = PsiShortNamesCache.getInstance(project);
            presentation.setEnabledAndVisible(true);
        } else {
            presentation.setEnabledAndVisible(false);
        }
    }

    @Override
    public void actionPerformed(AnActionEvent event) {
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        Project project = event.getData(CommonDataKeys.PROJECT);
        PsiFile psiFile = event.getData(CommonDataKeys.PSI_FILE);

        PsiFile file = null;
        if (editor != null && project != null && psiFile instanceof PsiJavaFile) {
            file = getXmlFileFromLayout(psiFile, editor);
        }
        if (file != null) {
            List<String> ids = getIdsFromLayout(file, new ArrayList<>());
            if (ids == null || ids.size() == 0) {
                showError("layout don't have subView");
                return;
            }
            WriteCommandAction.runWriteCommandAction(project, () -> writeCode(project, editor, psiFile, ids));
        }
    }

    private void writeCode(Project project, Editor editor, PsiFile psiFile, List<String> ids) {
        PsiClass psiClass = getTargetClass(editor, psiFile);
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        if (psiClass != null) {
            isActivity = false;
            boolean isAdapter = false;
            boolean isFragment = false;
            PsiFile[] xmlFiles = mNamesCache.getFilesByName("AndroidManifest.xml");
            XmlFile xmlFile = (XmlFile) xmlFiles[0];
            XmlTag manifest = xmlFile.getRootTag();
            String packageName = null;
            if (manifest != null) {
                packageName = manifest.getAttributeValue("package");
            }
            XmlTag[] tags = new XmlTag[0];
            if (manifest != null) {
                tags = manifest.getSubTags();
            }
            if (tags.length == 0) {
                log("tags.length======" + tags.length);
                return;
            }

            psiClassIsActivity(project, psiClass);
//            for (XmlTag tag : tags) {
//                if (tag.getName().equals("application")) {
//                    for (XmlTag subTag : tag.getSubTags()) {
//                        if (subTag.getAttributeValue("android:name").contains(psiClass.getName())) {
//                            isActivity = true;
//                        }
//                    }
//                }
//            }

            PsiClass clazz = JavaPsiFacade.getInstance(project).findClass("butterknife.BindView", new EverythingGlobalScope(project));
            JavaCodeStyleManager styleManager = JavaCodeStyleManager.getInstance(psiClass.getProject());
            PsiMethod[] methods;

            if (isActivity) {
                methods = psiClass.findMethodsByName("onCreate", false);
                if (methods.length == 0) {
                    showError("must be activity or fragment and adapter layout");
                    return;
                }
            } else {
                methods = psiClass.findMethodsByName("onCreateView", false);
                if (methods.length == 0) {
                    methods = psiClass.findMethodsByName("onCreateViewHolder", false);
                    if (methods.length == 0) {
                        showError("must be activity or fragment and adapter layout");
                        return;
                    } else {
                        isAdapter = true;
                    }
                } else {
                    isFragment = true;
                }
            }
            PsiMethod method = null;
            for (PsiMethod method1 : methods) {
                if ((isActivity && method1.getName().equals("onCreate"))
                        || (isFragment && method1.getName().equals("onCreateView"))
                        || (isAdapter && method1.getName().equals("onCreateViewHolder"))) {
                    method = method1;
                }
            }
            String viewStr = "";
            boolean haveBind = false;
            boolean haveBindView = false;
            String context = isActivity ? "this" : "getContext()";
            if (method != null && method.getBody() != null) {
                for (PsiStatement statement : method.getBody().getStatements()) {
                    if (statement.getText().contains("ButterKnife.bind(this);")) {
                        haveBind = true;
                    }
                    if (statement.getText().contains("ButterKnife.bind(this,")) {
                        haveBindView = true;
                    }
                }
            }

            PsiStatement before = null;
            PsiClass classHolder = null;
            PsiMethod holder = null;
            if (method != null && method.getBody() != null) {
                for (PsiStatement statement : method.getBody().getStatements()) {
                    if (!haveBind && isActivity && statement.getText().contains("setContentView") && clazz != null) {
                        PsiElement bind = factory.createStatementFromText(generateBindView(true, ""), psiClass);
                        styleManager.shortenClassReferences(method.getBody().addAfter(bind, statement));
                    }
                    if (isFragment && statement.getText().contains("inflater.inflate")) {
                        viewStr = statement.getText().substring(5, statement.getText().indexOf(" ="));
                        if (!haveBindView && clazz != null) {
                            PsiElement bind = factory.createStatementFromText(generateBindView(false, viewStr), psiClass);
                            styleManager.shortenClassReferences(method.getBody().addAfter(bind, statement));
                            haveBindView = true;
                        }
                    }
                    if (isFragment && statement.getText().contains("return " + viewStr + ";")) {
                        before = statement;
                    }
                }
                if (isAdapter) {
                    classHolder = psiClass.findInnerClassByName("ViewHolder", false);
                    PsiStatement superStatement = null;
                    if (classHolder != null) {
                        holder = classHolder.findMethodsByName("ViewHolder", false)[0];
                        if (holder != null && holder.getBody() != null) {
                            String txt = "";
                            for (PsiStatement statement : holder.getBody().getStatements()) {
                                if (statement.getText().contains("super(")) {
                                    superStatement = statement;
                                    txt = statement.getText().substring(statement.getText().indexOf("(") + 1, statement.getText().indexOf(")"));
                                }
                                if (statement.getText().contains("ButterKnife.bind(this,")) {
                                    haveBindView = true;
                                }
                            }
                            if (clazz != null && !haveBindView) {
                                PsiElement psiElement = factory.createStatementFromText(generateBindView(false, txt), classHolder);
                                styleManager.shortenClassReferences(holder.getBody().addAfter(psiElement, superStatement));
                            }
                        } else {
                            showError("can't found ViewHolder method");
                        }
                    } else {
                        showError("can't found ViewHolder Class");
                    }
                }
            }

            if (clazz == null) {
                for (String id : ids) {
                    String[] view = id.split("\\+");
                    String type = view[0];
                    String viewId = view[1];
                    String field = lineToUpper(viewId);
                    String prefix = lineToUpperField(viewId);
                    if (isAdapter) {
                        if (classHolder != null) {
                            if (!fieldIsExist(classHolder, field)) {
                                PsiField psiField = factory.createFieldFromText(generateFieldByFindById(type, field), classHolder);
                                styleManager.shortenClassReferences(classHolder.add(psiField));
                                if (type.equals("android.support.v7.widget.RecyclerView")) {
                                    PsiField adapter = factory.createFieldFromText(generateAdapterField(prefix, field), psiClass);
                                    styleManager.shortenClassReferences(psiClass.add(adapter));
                                }
                                String txt = "";
                                if (holder != null && holder.getBody() != null) {
                                    for (PsiStatement statement : holder.getBody().getStatements()) {
                                        if (statement.getText().contains("super(")) {
                                            txt = statement.getText().substring(statement.getText().indexOf("(") + 1, statement.getText().indexOf(")"));
                                        }
                                    }
                                    PsiElement psiElement = factory.createStatementFromText(generateAdapterFieldFindIdByFindById(txt, type, viewId, field), classHolder);
                                    styleManager.shortenClassReferences(holder.getBody().add(psiElement));
                                }
                            } else {
                                log("field -> " + field + " <- is already exist");
                                showInfo("field -> " + field + " <- is already exist");
                            }
                        } else {
                            showError("can't found ViewHolder Class");
                        }
                    } else {
                        if (!fieldIsExist(psiClass, field)) {
                            PsiField psiField = factory.createFieldFromText(generateFieldByFindById(type, field), psiClass);
                            styleManager.shortenClassReferences(psiClass.add(psiField));
                            PsiElement psiElement = factory.createStatementFromText(generateFieldFindIdByFindById(isActivity, viewStr, type, viewId, field), psiClass);
                            PsiElement element = null;
                            PsiElement element1 = null;
                            PsiElement element2 = null;
                            if (type.equals("android.support.v7.widget.RecyclerView")) {
                                PsiField adapter = factory.createFieldFromText(generateAdapterField(prefix, field), psiClass);
                                styleManager.shortenClassReferences(psiClass.add(adapter));
//
                                element = factory.createStatementFromText(generateSetting(field, context), psiClass);
                                element1 = factory.createStatementFromText(generateSetting1(field, prefix), psiClass);
                                element2 = factory.createStatementFromText(generateSetting2(field), psiClass);
                            }
                            if (method != null && method.getBody() != null) {
                                styleManager.shortenClassReferences(method.getBody().addBefore(psiElement, before));
                                if (element != null) {
                                    styleManager.shortenClassReferences(method.getBody().addBefore(element, before));
                                    styleManager.shortenClassReferences(method.getBody().addBefore(element1, before));
                                    styleManager.shortenClassReferences(method.getBody().addBefore(element2, before));
                                }
                            }
                        } else {
                            log("field -> " + field + " <- is already exist");
                            showInfo("field -> " + field + " <- is already exist");
                        }
                    }
                }
            } else {
                for (String id : ids) {
                    String[] view = id.split("\\+");
                    String type = view[0];
                    String viewId = view[1];
                    String field = lineToUpper(viewId);
                    String prefix = lineToUpperField(viewId);
                    if (method != null && method.getBody() != null) {
                        if (isAdapter) {
                            if (classHolder != null) {
                                if (!fieldIsExist(classHolder, field)) {
                                    PsiField psiField = factory.createFieldFromText(generateFieldByButterKnife(type, viewId, field), classHolder);
                                    styleManager.shortenClassReferences(classHolder.add(psiField));
                                    if (!fieldIsExist(psiClass, field + "Adapter")) {
                                        if (type.equals("android.support.v7.widget.RecyclerView")) {
                                            PsiField adapter = factory.createFieldFromText(generateAdapterField(prefix, field), psiClass);
                                            styleManager.shortenClassReferences(psiClass.add(adapter));
                                        }
                                    } else {
                                        log("field -> " + field + "Adapter <- is already exist");
                                        showInfo("field -> " + field + "Adapter <- is already exist");
                                    }
                                } else {
                                    log("field -> " + field + " <- is already exist");
                                    showInfo("field -> " + field + " <- is already exist");
                                }
                            } else {
                                showError("can't found ViewHolder Class");
                            }
                        } else {
                            if (!fieldIsExist(psiClass, field)) {
                                PsiField psiField = factory.createFieldFromText(generateFieldByButterKnife(type, viewId, field), psiClass);
                                styleManager.shortenClassReferences(psiClass.add(psiField));
                                PsiElement element = null;
                                PsiElement element1 = null;
                                PsiElement element2 = null;
                                if (type.equals("android.support.v7.widget.RecyclerView")) {
                                    element = factory.createStatementFromText(generateSetting(field, context), psiClass);
                                    element1 = factory.createStatementFromText(generateSetting1(field, prefix), psiClass);
                                    element2 = factory.createStatementFromText(generateSetting2(field), psiClass);
                                }
                                if (element != null) {
                                    styleManager.shortenClassReferences(method.getBody().addBefore(element, before));
                                    styleManager.shortenClassReferences(method.getBody().addBefore(element1, before));
                                    styleManager.shortenClassReferences(method.getBody().addBefore(element2, before));
                                }
                            } else {
                                log("field -> " + field + " <- is already exist");
                                showInfo("field -> " + field + " <- is already exist");
                            }
                        }
                    }
                }

                for (String id : ids) {
                    String[] view = id.split("\\+");
                    String type = view[0];
                    String viewId = view[1];
                    String field = lineToUpper(viewId);
                    String prefix = lineToUpperField(viewId);
                    if (!fieldIsExist(psiClass, field + "Adapter")) {
                        if (type.equals("android.support.v7.widget.RecyclerView")) {
                            PsiField adapter = factory.createFieldFromText(generateAdapterField(prefix, field), psiClass);
                            styleManager.shortenClassReferences(psiClass.add(adapter));
                        }
                    } else {
                        log("field -> " + field + "Adapter <- is already exist");
                        showInfo("field -> " + field + "Adapter <- is already exist");
                    }
                }
            }
            for (String id : ids) {
                String[] view = id.split("\\+");
                String type = view[0];
                String viewId = view[1];
                String prefix = lineToUpperField(viewId);
                if (type.equals("android.support.v7.widget.RecyclerView")) {
                    PsiClass parentClass = mNamesCache.getClassesByName(psiFile.getName().replace(".java", ""), new EverythingGlobalScope(project))[0];
                    if (parentClass.findInnerClassByName(prefix + "Adapter", false) == null) {
                        PsiClass adapter = factory.createClass(prefix + "Adapter");
                        adapter.getModifierList().setModifierProperty("public", false);
                        PsiClass viewHolder = factory.createClass("ViewHolder");
                        viewHolder.getModifierList().setModifierProperty("public", false);
                        PsiElement element = factory.createFQClassNameReferenceElement("RecyclerView.Adapter<" + prefix + "Adapter.ViewHolder>", new EverythingGlobalScope(project));
                        adapter.getExtendsList().add(element);
                        PsiClass superViewHolder = JavaPsiFacade.getInstance(project).findClass("android.support.v7.widget.RecyclerView.ViewHolder", new EverythingGlobalScope(project));
                        if (superViewHolder != null) {
                            viewHolder.getExtendsList().add(factory.createClassReferenceElement(superViewHolder));
                        }

                        XmlFile itemXml = (XmlFile) PsiFileFactory.getInstance(project).createFileFromText(viewId + "_item.xml", StdFileTypes.XML, generateItemXml());
                        findResDir(psiFile.getParent());
                        PsiDirectory layoutDir = mResDir.findSubdirectory("layout");
                        PsiFile itemFile = layoutDir.findFile(itemXml.getName());
                        if (itemFile == null) {
                            layoutDir.add(itemXml);
                        }

                        PsiMethod createViewHolder = factory.createMethodFromText(generateCreateViewHolder(packageName, viewId), parentClass);
                        PsiMethod bindViewHolder = factory.createMethodFromText(generateBindViewHolder(), parentClass);
                        PsiMethod getItemCount = factory.createMethodFromText(generateGetItemCount(), parentClass);
                        styleManager.shortenClassReferences(adapter.add(createViewHolder));
                        styleManager.shortenClassReferences(adapter.add(bindViewHolder));
                        styleManager.shortenClassReferences(adapter.add(getItemCount));

                        PsiMethod viewHolderConstructor = factory.createMethodFromText(generateViewHolderConstructor(clazz), parentClass);
                        styleManager.shortenClassReferences(viewHolder.add(viewHolderConstructor));
                        adapter.add(viewHolder);
                        parentClass.add(adapter);
                    } else {
                        log("class -> " + prefix + "Adapter <- is already exist");
                        showInfo("class -> " + prefix + "Adapter <- is already exist");
                    }
                }
            }


            for (String id : ids) {
                String[] view = id.split("\\+");
                String type = view[0];
                if (!type.contains(".") && !type.startsWith("android.support")) {
                    PsiClass importClass = JavaPsiFacade.getInstance(project).findClass("android.widget." + type, new EverythingGlobalScope(project));
                    if (importClass != null) {
                        ((PsiJavaFile) psiFile).getImportList().add(factory.createImportStatement(importClass));
                    }
                }
            }
        }
    }

    private void psiClassIsActivity(Project project, PsiClass psiClass) {
        if (psiClass.getExtendsList() == null) {
            log("extendsList === null");
            return;
        }
        if (psiClass.getExtendsList().getReferenceElements().length == 0) {
            log("psiClass.getExtendsList().getReferenceElements().length ==== 0");
            return;
        }
        if (psiClass.getExtendsList() != null && psiClass.getExtendsList().getReferenceElements().length != 0) {
//            log("extends name =====  " + psiClass.getExtendsList().getReferenceElements()[0].getQualifiedName());
            if (!psiClass.getExtendsList().getReferenceElements()[0].getQualifiedName().equals("android.app.Activity")) {
                String name = psiClass.getExtendsList().getReferenceElements()[0].getQualifiedName();
                PsiClass clazz = JavaPsiFacade.getInstance(project).findClass(name, new EverythingGlobalScope(project));
                psiClassIsActivity(project, clazz);
            } else {
                isActivity = true;
            }
        }
        return false;
    }

    private void findResDir(PsiDirectory psiDirectory) {
        if (psiDirectory.findSubdirectory("res") == null) {
            findResDir(psiDirectory.getParent());
        } else {
            mResDir = psiDirectory.findSubdirectory("res");
        }
    }

    private boolean fieldIsExist(PsiClass psiClass, String fieldName) {
        boolean exist = false;
        PsiField[] fields = psiClass.getFields();
        if (fields.length != 0) {
            for (PsiField f : fields) {
                if (f.getName() != null && f.getName().equals(fieldName)) {
                    exist = true;
                }
            }
        }
        return exist;
    }

    private String generateFieldByFindById(String type, String field) {
        return type + " " + field + ";";
    }

    private String generateFieldFindIdByFindById(boolean b, String viewStr, String type, String viewId, String field) {
        return b ? field + " = (" + type + ") " + "findViewById(R.id." + viewId + ");"
                : field + " = (" + type + ") " + viewStr + ".findViewById(R.id." + viewId + ");";
    }

    private String generateAdapterFieldFindIdByFindById(String viewStr, String type, String viewId, String field) {
        return field + " = (" + type + ") " + viewStr + ".findViewById(R.id." + viewId + ");";
    }

    private String generateFieldByButterKnife(String type, String viewId, String field) {
        return "@butterknife.BindView(R.id." + viewId + ")" +
                "\n" + type + " " + field + ";";
    }

    private String generateBindView(boolean b, String viewStr) {
        return b ? "butterknife.ButterKnife.bind(this);" : "butterknife.ButterKnife.bind(this, " + viewStr + ");";
    }

    private String generateSetting(String field, String context) {
        return field + ".setLayoutManager(new android.support.v7.widget.LinearLayoutManager(" + context + "));\n";
    }

    private String generateSetting1(String field, String prefix) {
        return field + "Adapter = new " + prefix + "Adapter();\n";
    }

    private String generateSetting2(String field) {
        return field + ".setAdapter(" + field + "Adapter);\n";
    }

    private String generateAdapterField(String prefix, String field) {
        return "private " + prefix + "Adapter " + field + "Adapter;";
    }

    private String generateCreateViewHolder(String packageName, String viewId) {
        return "@Override\n" +
                "public ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {\n" +
                "    android.view.View view = android.view.LayoutInflater.from(parent.getContext()).inflate(" +
                packageName + ".R.layout." + viewId + "_item, parent, false);\n" +
                "    return new ViewHolder(view);\n" +
                "}";
    }

    private String generateBindViewHolder() {
        return "@Override\n" +
                "public void onBindViewHolder(ViewHolder holder, int position) {\n" +
                "\n" +
                "}";
    }

    private String generateGetItemCount() {
        return "@Override\n" +
                "public int getItemCount() {\n" +
                "    return 0;\n" +
                "}";
    }

    private String generateViewHolderConstructor(PsiClass clazz) {
        return "ViewHolder(View itemView) {" +
                "super(itemView);" +
                (clazz == null ? "" : "butterknife.ButterKnife.bind(this, itemView);") +
                "}";
    }

    private String generateItemXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                "    android:layout_width=\"match_parent\"\n" +
                "    android:layout_height=\"wrap_content\"\n" +
                "    android:orientation=\"vertical\">\n" +
                "\n" +
                "</LinearLayout>";
    }

    private static List<String> getIdsFromLayout(PsiFile file, ArrayList<String> ids) {
        file.accept(new XmlRecursiveElementVisitor() {
            @Override
            public void visitElement(PsiElement element) {
                super.visitElement(element);
                if (element instanceof XmlTag) {
                    XmlTag tag = (XmlTag) element;
                    if (tag.getName().equalsIgnoreCase("include")) {
                        XmlAttribute layout = tag.getAttribute("layout", null);

                        if (layout != null) {
                            PsiFile include = findIncludeXml(getLayoutName(layout.getValue()));
                            if (include != null) {
                                getIdsFromLayout(include, ids);

                                return;
                            }
                        }
                    }

                    String name = tag.getName();
                    String id = tag.getAttributeValue("android:id");

                    if (id != null && !TextUtils.isEmpty(id)) {
                        id = id.replace("@+id/", "");
                        ids.add(name + "+" + id);
                    }
                }
            }
        });
        return ids;
    }

    private static PsiFile getXmlFileFromLayout(PsiFile psiFile, Editor editor) {
        int offset = editor.getCaretModel().getOffset();
        PsiElement element = psiFile.findElementAt(offset);
        if (element instanceof PsiIdentifier) {

            PsiElement l = element.getParent().getFirstChild();
            if ("R.layout".equals(l.getText())) {
                String name = String.format("%s.xml", element.getText());
                PsiFile file = mNamesCache.getFilesByName(name)[0];
                if (file != null) {
                    return file;
                } else {
                    showError("can't found layout file");
                }
            } else {
                showError("must be layout file");
            }
        } else {
            showError("Element does not belong to PsiIdentifier");
        }
        return null;
    }

    private static PsiFile findIncludeXml(String fileName) {
        String name = String.format("%s.xml", fileName);
        PsiFile file = mNamesCache.getFilesByName(name)[0];
        if (file != null) {
            return file;
        } else {
            showError("can't found sub layout");
            return null;
        }
    }

    private static String getLayoutName(String layout) {
        if (layout == null || !layout.startsWith("@") || !layout.contains("/")) {
            return null; // it's not layout identifier
        }

        String[] parts = layout.split("/");
        if (parts.length != 2) {
            return null; // not enough parts
        }

        return parts[1];
    }

    private static void showError(String text) {
        mFactory.createHtmlTextBalloonBuilder(text, MessageType.ERROR, null)
                .setFadeoutTime(7500)
                .createBalloon()
                .show(RelativePoint.getCenterOf(mStatusBar.getComponent()), Balloon.Position.atRight);
    }

    private static void showInfo(String text) {
        mFactory.createHtmlTextBalloonBuilder(text, MessageType.INFO, null)
                .setFadeoutTime(7500)
                .createBalloon()
                .show(RelativePoint.getCenterOf(mStatusBar.getComponent()), Balloon.Position.atRight);
    }

    private String lineToUpperField(String str) {
        if (str.contains("_")) {
            String id = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, str);
            id = id.substring(0, 1).toUpperCase() + id.substring(1);
            return id;
        } else {
            return str.substring(0, 1).toUpperCase() + str.substring(1);
        }
    }

    private String lineToUpper(String str) {
        return "m" + lineToUpperField(str);
    }

    private PsiClass getTargetClass(Editor editor, PsiFile file) {
        int offset = editor.getCaretModel().getOffset();
        PsiElement element = file.findElementAt(offset);
        if (element == null) {
            return null;
        } else {
            PsiClass target = PsiTreeUtil.getParentOfType(element, PsiClass.class);
            return target instanceof SyntheticElement ? null : target;
        }
    }
}
