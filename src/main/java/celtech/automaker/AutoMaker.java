/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package celtech.automaker;

import celtech.appManager.ApplicationMode;
import celtech.appManager.TaskController;
import celtech.configuration.ApplicationConfiguration;
import celtech.configuration.MachineType;
import celtech.coreUI.DisplayManager;
import celtech.printerControl.Printer;
import celtech.printerControl.comms.RoboxCommsManager;
import celtech.utils.AutoUpdate;
import celtech.utils.AutoUpdateCompletionListener;
import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;
import javafx.application.Application;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.image.Image;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import libertysystems.configuration.ConfigNotLoadedException;
import libertysystems.configuration.Configuration;
import libertysystems.stenographer.Stenographer;
import libertysystems.stenographer.StenographerFactory;
import org.controlsfx.control.action.Action;
import org.controlsfx.dialog.Dialogs;

/**
 *
 * @author Ian Hudson @ Liberty Systems Limited
 */
public class AutoMaker extends Application implements AutoUpdateCompletionListener
{

    private static final Stenographer steno = StenographerFactory.getStenographer(AutoMaker.class.getName());
    private static Configuration configuration = null;
    private static DisplayManager displayManager = null;
    private ResourceBundle i18nBundle = null;
    private RoboxCommsManager commsManager = null;
    private AutoUpdate autoUpdater = null;
    private Dialogs.CommandLink dontShutDown = null;
    private Dialogs.CommandLink shutDownAndTerminate = null;
    private Dialogs.CommandLink shutDownWithoutTerminating = null;

    @Override
    public void start(Stage stage) throws Exception
    {
        stage.getIcons().addAll(new Image(getClass().getResourceAsStream("/celtech/automaker/resources/images/AutomakerIcon_256x256.png")),
                                new Image(getClass().getResourceAsStream("/celtech/automaker/resources/images/AutomakerIcon_64x64.png")),
                                new Image(getClass().getResourceAsStream("/celtech/automaker/resources/images/AutomakerIcon_32x32.png")));

        String installDir = ApplicationConfiguration.getApplicationInstallDirectory(AutoMaker.class);

        commsManager = RoboxCommsManager.getInstance(ApplicationConfiguration.getBinariesDirectory());

        try
        {
            configuration = Configuration.getInstance();
        } catch (ConfigNotLoadedException ex)
        {
            steno.error("Couldn't load application configuration");
        }

        displayManager = DisplayManager.getInstance();
        i18nBundle = DisplayManager.getLanguageBundle();

        checkMachineTypeRecognised();
        check3DSupported();

        String applicationName = i18nBundle.getString("application.title");
        displayManager.configureDisplayManager(stage, applicationName);

        dontShutDown = new Dialogs.CommandLink(i18nBundle.getString("dialogs.dontShutDownTitle"), i18nBundle.getString("dialogs.dontShutDownMessage"));
        shutDownAndTerminate = new Dialogs.CommandLink(i18nBundle.getString("dialogs.shutDownAndTerminateTitle"), i18nBundle.getString("dialogs.shutDownAndTerminateMessage"));
        shutDownWithoutTerminating = new Dialogs.CommandLink(i18nBundle.getString("dialogs.shutDownAndDontTerminateTitle"), i18nBundle.getString("dialogs.shutDownAndDontTerminateMessage"));

        VBox statusSupplementaryPage = null;

        try
        {
            URL mainPageURL = getClass().getResource("/celtech/automaker/resources/fxml/SupplementaryStatusPage.fxml");
            FXMLLoader configurationSupplementaryStatusPageLoader = new FXMLLoader(mainPageURL, i18nBundle);
            statusSupplementaryPage = (VBox) configurationSupplementaryStatusPageLoader.load();
        } catch (IOException ex)
        {
            steno.error("Failed to load supplementary status page:" + ex.getMessage());
            System.err.println(ex);
        }

        VBox statusSlideOutHandle = displayManager.getSidePanelSlideOutHandle(ApplicationMode.STATUS);

        if (statusSlideOutHandle != null)
        {
            statusSlideOutHandle.getChildren().add(statusSupplementaryPage);
            VBox.setVgrow(statusSupplementaryPage, Priority.ALWAYS);
        }

        stage.setOnCloseRequest((WindowEvent event) ->
        {
            boolean transferringDataToPrinter = false;

            for (Printer printer : commsManager.getPrintStatusList())
            {
                transferringDataToPrinter = printer.getPrintQueue().sendingDataToPrinterProperty().get();
                if (transferringDataToPrinter)
                {
                    Action shutDownResponse = Dialogs.create().title(i18nBundle.getString("dialogs.printJobsAreStillTransferringTitle"))
                            .message(i18nBundle.getString("dialogs.printJobsAreStillTransferringMessage"))
                            .masthead(null)
                            .showCommandLinks(dontShutDown, dontShutDown, shutDownAndTerminate);

                    if (shutDownResponse == dontShutDown)
                    {
                        event.consume();
                    }
                    break;
                }
            }
        });

        final AutoUpdateCompletionListener completeListener = this;

        stage.setOnShown((WindowEvent event) ->
        {
            autoUpdater = new AutoUpdate("AutoMaker", "0abc523fc24", completeListener);
            autoUpdater.start();
        });

        stage.show();
    }

    /**
     * Check that the machine type is fully recognised and if not then exit the
     * application.
     */
    private void checkMachineTypeRecognised()
    {
        MachineType machineType = ApplicationConfiguration.getMachineType();
        if (machineType.equals(MachineType.UNKNOWN))
        {
            Dialogs.create()
                    .owner(null)
                    .title(i18nBundle.getString("dialogs.fatalErrorDetectingMachineType"))
                    .masthead(null)
                    .message(i18nBundle.getString("dialogs.automakerUnknownMachineType"))
                    .showError();
            steno.error("Closing down due to unrecognised machine type.");
            Platform.exit();
        }
    }

    /**
     * Check that 3D is supported on this machine and if not then exit the
     * application.
     */
    private void check3DSupported()
    {
        if (!Platform.isSupported(ConditionalFeature.SCENE3D))
        {
            Dialogs.create()
                    .owner(null)
                    .title(i18nBundle.getString("dialogs.fatalErrorNo3DSupport"))
                    .masthead(null)
                    .message(i18nBundle.getString("dialogs.automakerErrorNo3DSupport"))
                    .showError();
            steno.error("Closing down due to lack of required 3D support.");
            Platform.exit();
        }
    }

    @Override
    public void stop() throws Exception
    {
        autoUpdater.shutdown();
        displayManager.shutdown();
        commsManager.shutdown();

        TaskController taskController = TaskController.getInstance();

        if (taskController.getNumberOfManagedTasks() > 0)
        {
            Thread.sleep(5000);
            taskController.shutdownAllManagedTasks();
        }
    }

    /**
     * The main() method is ignored in correctly deployed JavaFX application.
     * main() serves only as fallback in case the application can not be
     * launched through deployment artifacts, e.g., in IDEs with limited FX
     * support. NetBeans ignores main().
     *
     * @param args the command line arguments
     */
    public static void main(String[] args)
    {
        launch(args);
    }

    @Override
    public void autoUpdateComplete(boolean requiresShutdown)
    {
        if (requiresShutdown)
        {
            Platform.exit();
        } else
        {
            commsManager.start();
        }
    }
}
