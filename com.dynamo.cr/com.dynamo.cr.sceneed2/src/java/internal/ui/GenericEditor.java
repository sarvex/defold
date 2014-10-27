package internal.ui;

import java.io.ByteArrayInputStream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.dialogs.SaveAsDialog;
import org.eclipse.ui.part.EditorPart;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.statushandlers.StatusManager;

import clojure.lang.Keyword;
import clojure.lang.RT;
import clojure.osgi.ClojureHelper;
import clojure.osgi.internal.ClojureOSGiActivator;

/**
 * This class is here for Eclipse to instantiate. Use this as the editor class
 * for any extensions to the org.eclipse.ui.editors extension point.
 *
 * The sequence of events goes like this:
 * <ol>
 * <li>The workbench instantiates this class via the no-arg constructor</li>
 * <li>The workbench calls init() with the site and input.</li>
 * <li>This generic class dynamically invokes
 * internal.ui.editors/implementation-for to locate the real implementation</li>
 * <li>All further functions delegate to that implementation.</li>
 *
 * Why all this machinery? Basically, Eclipse needs a compiled class to
 * instantiate. We could implement that with Clojure, but only if we do ahead of
 * time (AOT) compilation. Trouble is, AOT compilation doesn't work at all well
 * with OSGi bundles. Add in the native libs for JOGL, and we just can't get AOT
 * to work at all.
 *
 * So this class is here to be compiled (all Java is AOT, inherently!) and
 * dynamically delegate to a Clojure implementation.
 *
 * @author mtnygard
 *
 */
public class GenericEditor extends EditorPart {
    private static final String INTERNAL_NS = "internal.ui.editors";
    private static final String NODE_NS = "dynamo.node";

    // Keywords that are event types
    private static final Keyword INIT = RT.keyword(null, "init");
    private static final Keyword SAVE = RT.keyword(null, "save");
    private static final Keyword CREATE = RT.keyword(null, "create");
    private static final Keyword FOCUS = RT.keyword(null, "focus");
    private static final Keyword DESTROY = RT.keyword(null, "destroy");

    // Keywords that go into the event maps
    private static final Keyword PARENT = RT.keyword(null, "parent");
    private static final Keyword SITE = RT.keyword(null, "site");
    private static final Keyword INPUT = RT.keyword(null, "input");
    private static final Keyword FILE = RT.keyword(null, "file");
    private static final Keyword MONITOR = RT.keyword(null, "monitor");

    /**
     * This is a Clojure variable that provides the real implementation plugged
     * in to this generic editor.
     */
    private Object behavior;

    static {
        ClojureHelper.require(INTERNAL_NS);
        ClojureHelper.require(NODE_NS);
    }

    @Override
    public void init(IEditorSite site, IEditorInput input) throws PartInitException {
        setSite(site);
        setInput(input);
        setPartName(input.getName());

        IFile file = ((IFileEditorInput) input).getFile();
        behavior = ClojureHelper.invoke(INTERNAL_NS, "implementation-for", site, file);

        ClojureHelper.invoke(NODE_NS, "dispatch-message", behavior, INIT, SITE, site, INPUT, input);
    }

    @Override
    public void createPartControl(Composite parent) {
        ClojureHelper.invoke(NODE_NS, "dispatch-message", behavior, CREATE, PARENT, parent);
    }

    @Override
    public void doSave(IProgressMonitor monitor) {
        ClojureHelper.invoke(NODE_NS, "dispatch-message", behavior, SAVE, FILE, ((IFileEditorInput) getEditorInput()).getFile(), MONITOR, monitor);
    }

    @Override
    public void setFocus() {
        ClojureHelper.invoke(NODE_NS, "dispatch-message", behavior, FOCUS);
    }

    @Override
    public void dispose() {
        ClojureHelper.invoke(NODE_NS, "dispatch-message", behavior, DESTROY);
        super.dispose();
    }

    @Override
    public void doSaveAs() {
        IFileEditorInput input = (IFileEditorInput) getEditorInput();
        IFile file = input.getFile();
        SaveAsDialog dialog = new SaveAsDialog(getSite().getShell());
        dialog.setOriginalFile(file);
        dialog.create();

        if (dialog.open() == Window.OK) {
            IPath filePath = dialog.getResult();
            if (filePath == null) {
                return;
            }

            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IFile newFile = workspace.getRoot().getFile(filePath);

            try {
                newFile.create(new ByteArrayInputStream(new byte[0]), IFile.FORCE, new NullProgressMonitor());
            } catch (CoreException e) {
                Status status = new Status(IStatus.ERROR, ClojureOSGiActivator.PLUGIN_ID, 0, e.getMessage(), null);
                StatusManager.getManager().handle(status, StatusManager.LOG | StatusManager.SHOW);
                return;
            }
            FileEditorInput newInput = new FileEditorInput(newFile);
            setInput(newInput);
            setPartName(newInput.getName());

            IStatusLineManager lineManager = getEditorSite().getActionBars().getStatusLineManager();
            IProgressMonitor pm = lineManager.getProgressMonitor();
            doSave(pm);
        }
    }

    @Override
    public boolean isDirty() {
        return true;
    }

    @Override
    public boolean isSaveAsAllowed() {
        return true;
    }

    public Object getBehavior() {
        return behavior;
    }
}
