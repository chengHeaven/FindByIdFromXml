package com.github.chengheaven;

import com.google.common.base.CaseFormat;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
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
            boolean isActivity = false;
            boolean isAdapter = false;
            boolean isFragment = false;
            PsiFile[] xmlFiles = mNamesCache.getFilesByName("AndroidManifest.xml");
            XmlFile xmlFile = (XmlFile) xmlFiles[0];
            XmlTag manifest = xmlFile.getRootTag();
            XmlTag[] tags = new XmlTag[0];
            if (manifest != null) {
                tags = manifest.getSubTags();
            }
            for (XmlTag tag : tags) {
                if (tag.getName().equals("application")) {
                    for (XmlTag subTag : tag.getSubTags()) {
                        if (subTag.getAttributeValue("android:name").contains(psiClass.getName())) {
                            isActivity = true;
                        }
                    }
                }
            }
            PsiClass clazz = JavaPsiFacade.getInstance(project).findClass("butterknife.BindView", new EverythingGlobalScope(project));
            JavaCodeStyleManager styleManager = JavaCodeStyleManager.getInstance(psiClass.getProject());
            PsiMethod[] methods;

            if (isActivity) {
                methods = psiClass.findMethodsByName("onCreate", false);
                if (methods.length == 0) {
                    showError("must be activity or fragment layout");
                    return;
                }
            } else {
                methods = psiClass.findMethodsByName("onCreateView", false);
                if (methods.length == 0) {
                    methods = psiClass.findMethodsByName("onCreateViewHolder", false);
                    if (methods.length == 0) {
                        showError("can't found layout");
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

            if (clazz == null) {
                PsiStatement viewStatement = null;
                PsiStatement before = null;
                if (method != null && method.getBody() != null) {
                    for (PsiStatement statement : method.getBody().getStatements()) {
                        if (isFragment && statement.getText().contains("inflater.inflate")) {
                            viewStr = statement.getText().substring(5, statement.getText().indexOf(" ="));
                            viewStatement = statement;
                        }
                        if (isFragment && statement.getText().contains("return " + viewStr + ";")) {
                            before = statement;
                        }
                    }
                }
                for (String id : ids) {
                    String[] view = id.split("\\+");
                    String type = view[0];
                    String viewId = view[1];
                    String field = lineToUpper(viewId);
                    if (isAdapter) {
                        PsiClass classHolder = psiClass.findInnerClassByName("ViewHolder", false);
                        if (classHolder != null) {
                            if (!fieldIsExist(classHolder, field)) {
                                PsiField psiField = factory.createFieldFromText(generateFieldByFindById(type, field), classHolder);
                                styleManager.shortenClassReferences(classHolder.add(psiField));
                                PsiMethod holder = classHolder.findMethodsByName("ViewHolder", false)[0];
                                String txt = "";
                                if (holder.getBody() != null) {
                                    for (PsiStatement statement : holder.getBody().getStatements()) {
                                        if (statement.getText().contains("super(")) {
                                            txt = statement.getText().substring(statement.getText().indexOf("(") + 1, statement.getText().indexOf(")"));
                                        }
                                    }
                                }
                                PsiElement psiElement = factory.createStatementFromText(generateAdapterFieldFindIdByFindById(txt, type, viewId, field), classHolder);
                                styleManager.shortenClassReferences(holder.getBody().add(psiElement));
                            } else {
                                log(field + " is already exist");
                                showInfo(field + " is already exist");
                            }
                        } else {
                            showError("can't found ViewHolder Class");
                        }
                    } else {
                        if (!fieldIsExist(psiClass, field)) {
                            PsiField psiField = factory.createFieldFromText(generateFieldByFindById(type, field), psiClass);
                            styleManager.shortenClassReferences(psiClass.add(psiField));
                            PsiElement psiElement = factory.createStatementFromText(generateFieldFindIdByFindById(isActivity, viewStr, type, viewId, field), psiClass);
                            if (method != null && method.getBody() != null) {
                                styleManager.shortenClassReferences(method.getBody().addBefore(psiElement, before));
                            }
                        } else {
                            log(field + " is already exist");
                            showInfo(field + " is already exist");
                        }
                    }
                }
            } else {
                for (String id : ids) {
                    if (method != null && method.getBody() != null) {
                        String[] view = id.split("\\+");
                        String type = view[0];
                        String viewId = view[1];
                        String field = lineToUpper(viewId);
                        if (isAdapter) {
                            PsiClass classHolder = psiClass.findInnerClassByName("ViewHolder", false);
                            if (classHolder != null) {
                                if (!fieldIsExist(classHolder, field)) {
                                    PsiField psiField = factory.createFieldFromText(generateFieldByButterKnife(type, viewId, field), classHolder);
                                    styleManager.shortenClassReferences(classHolder.add(psiField));
                                } else {
                                    log(field + " is already exist");
                                    showInfo(field + " is already exist");
                                }
                            } else {
                                showError("can't found ViewHolder Class");
                            }
                        } else {

                            if (!fieldIsExist(psiClass, field)) {
                                PsiField psiField = factory.createFieldFromText(generateFieldByButterKnife(type, viewId, field), psiClass);
                                styleManager.shortenClassReferences(psiClass.add(psiField));
                            } else {
                                log(field + " is already exist");
                                showInfo(field + " is already exist");
                            }
                        }
                    }
                }
                if (method != null && method.getBody() != null) {
                    for (PsiStatement statement : method.getBody().getStatements()) {
                        if (!haveBind && isActivity && statement.getText().contains("setContentView")) {
                            PsiElement bind = factory.createStatementFromText(generateBindView(true, ""), psiClass);
                            styleManager.shortenClassReferences(method.getBody().add(bind));
                        }
                        if (!isActivity && statement.getText().contains("inflater.inflate")) {
                            viewStr = statement.getText().substring(5, statement.getText().indexOf(" ="));
                            if (!haveBindView) {
                                PsiElement bind = factory.createStatementFromText(generateBindView(false, viewStr), psiClass);
                                styleManager.shortenClassReferences(method.getBody().addAfter(bind, statement));
                                haveBindView = true;
                            }
                        }
                    }
                }
            }
            if (method != null && method.getBody() != null) {
                if (isAdapter) {
                    PsiClass classHolder = psiClass.findInnerClassByName("ViewHolder", false);
                    PsiStatement superStatement = null;
                    if (classHolder != null) {
                        PsiMethod holder = classHolder.findMethodsByName("ViewHolder", false)[0];
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
                    showError("can't found layout");
                }
            } else {
                showError("can't found layout");
            }
        } else {
            showError("can't found layout");
        }
        return null;
    }

    private static PsiFile findIncludeXml(String fileName) {
        String name = String.format("%s.xml", fileName);
        PsiFile file = mNamesCache.getFilesByName(name)[0];
        if (file != null) {
            return file;
        } else {
            showError("can't found layout");
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
