package com.github.forax.pro.plugin.linker;

import static com.github.forax.pro.api.helper.OptionAction.action;
import static com.github.forax.pro.api.helper.OptionAction.exists;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;

import com.github.forax.pro.api.Config;
import com.github.forax.pro.api.MutableConfig;
import com.github.forax.pro.api.Plugin;
import com.github.forax.pro.api.WatcherRegistry;
import com.github.forax.pro.api.helper.CmdLine;
import com.github.forax.pro.api.helper.ProConf;
import com.github.forax.pro.api.helper.OptionAction;
import com.github.forax.pro.helper.FileHelper;
import com.github.forax.pro.helper.Log;
import com.github.forax.pro.helper.ModuleHelper;
import com.github.forax.pro.helper.util.StableList;

public class LinkerPlugin implements Plugin {
  @Override
  public String name() {
    return "linker";
  }

  @Override
  public void init(MutableConfig config) {
    LinkerConf linker = config.getOrUpdate(name(), LinkerConf.class);
    linker.compressLevel(0);
    linker.stripDebug(false);
    linker.stripNativeCommands(false);
    linker.includeSystemJMODs(false);
  }
  
  @Override
  public void configure(MutableConfig config) {
    LinkerConf linker = config.getOrUpdate(name(), LinkerConf.class);
    ConventionFacade convention = config.getOrThrow("convention", ConventionFacade.class);
    
    // inputs
    linker.systemModulePath(convention.javaHome().resolve("jmods"));
    linker.moduleArtifactSourcePath(convention.javaModuleArtifactSourcePath());
    linker.moduleDependencyPath(convention.javaModuleDependencyPath());
    
    // outputs
    linker.destination(convention.javaLinkerImagePath());
  }
  
  @Override
  public void watch(Config config, WatcherRegistry registry) {
    LinkerConf linker = config.getOrThrow(name(), LinkerConf.class);
    
    registry.watch(linker.systemModulePath());
    linker.moduleDependencyPath().forEach(registry::watch);
    registry.watch(linker.moduleArtifactSourcePath());
  }
  
  public static OptionAction<Jlink> launcherAction(String optionName, Function<? super Jlink, ? extends Map<String,String>> mapper) {
    return config -> Optional.of(line -> {
      mapper.apply(config).forEach((name, mainClass) -> line.add(optionName).add(name + '=' + mainClass));
      return line;
    });
  }
  
  enum JlinkOption {
    MODULE_PATH(action("--module-path", Jlink::modulePath, ":")),
    ROOT_MODULES(action("--add-modules", Jlink::rootModules, ",")),
    LAUNCHER(launcherAction("--launcher", Jlink::launchers)),
    COMPRESS(action("--compress", Jlink::compressLevel)),
    STRIP_DEBUG(exists("--strip-debug", Jlink::stripDebug)),
    STRIP_NATIVE_COMMANDS(exists("--strip-native-commands", Jlink::stripNativeCommands)),
    OUPUT(action("--output", Jlink::destination))
    ;
    
    final OptionAction<Jlink> action;
    
    private JlinkOption(OptionAction<Jlink> action) {
      this.action = action;
    }
  }
  
  private static String launcher(ModuleDescriptor moduleDescriptor) {
    return moduleDescriptor.name();  //TODO add user input
  }
  
  @Override
  public int execute(Config config) throws IOException {
    Log log = Log.create(name(), config.getOrThrow("pro", ProConf.class).loglevel());
    log.debug(config, conf -> "config " + config);
    
    ToolProvider jlinkTool = ToolProvider.findFirst("jlink")
        .orElseThrow(() -> new IllegalStateException("can not find jlink"));
    LinkerConf linker = config.getOrThrow(name(), LinkerConf.class);
    
    Path systemModulePath = linker.systemModulePath();
    if (!(Files.exists(systemModulePath))) {
      throw new IOException("unable to find system modules at " + systemModulePath);
    }
    
    ModuleFinder moduleFinder = ModuleFinder.of(linker.moduleArtifactSourcePath());
    Set<String> rootModules = linker.rootModules().map(HashSet::new).orElseGet(() -> {
      return moduleFinder.findAll().stream()
          .map(reference -> reference.descriptor().name())
          .collect(Collectors.toCollection(HashSet::new));
    });
    linker.serviceNames().ifPresent(serviceNames -> {
      ModuleFinder rootFinder = ModuleFinder.compose(moduleFinder, ModuleFinder.ofSystem());
      ModuleHelper.findAllModulesWhichProvideAService(serviceNames, rootFinder)
        .map(ref -> ref.descriptor().name())
        .forEach(rootModules::add);
    });
    
    // find launcher main classes
    Map<String, String> launchers = rootModules.stream()
      .flatMap(root -> moduleFinder.find(root).stream())
      .map(ModuleReference::descriptor)
      .filter(desc -> desc.mainClass().isPresent())
      .collect(Collectors.toMap(desc -> launcher(desc), desc -> desc.name() + '/' + desc.mainClass().get()));
    if (launchers.isEmpty()) {
      log.error(null, __ -> "no main class found among root modules");
      return 1; //FIXME
    }
    
    List<Path> modulePath =
        linker.modulePath()
          .orElseGet(() -> new StableList<Path>()
                .append(systemModulePath)
                .appendAll(FileHelper.pathFromFilesThatExist(linker.moduleDependencyPath()))
                .append(linker.moduleArtifactSourcePath()));
    
    log.debug(rootModules, roots -> "rootModules " + roots);
    log.debug(launchers, launcherMains -> "launchers " + launcherMains);
    Jlink jlink = new Jlink(linker, rootModules, launchers, modulePath);
    
    Path destination = linker.destination();
    FileHelper.deleteAllFiles(destination, true);
    
    String[] arguments = OptionAction.gatherAll(JlinkOption.class, option -> option.action).apply(jlink, new CmdLine()).toArguments();
    log.verbose(null, __ -> OptionAction.toPrettyString(JlinkOption.class, option -> option.action).apply(jlink, "jlink"));
    
    int errorCode = jlinkTool.run(System.out, System.err, arguments);
    if (errorCode != 0) {
      return errorCode; 
    }
    
    if (linker.includeSystemJMODs()) {
      Path jmods = destination.resolve("jmods");
      Files.createDirectories(jmods);
      try(DirectoryStream<Path> stream = Files.newDirectoryStream(systemModulePath)) {
        for(Path path: stream) {
          Files.copy(path, jmods.resolve(path.getFileName()));
        }
      }
    }
    return 0;
  }
}
