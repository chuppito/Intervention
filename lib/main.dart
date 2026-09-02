import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_tts/flutter_tts.dart';
import 'package:audioplayers/audioplayers.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:installed_apps/installed_apps.dart';
import 'package:installed_apps/app_info.dart';
import 'package:external_app_launcher/external_app_launcher.dart';
import 'package:file_picker/file_picker.dart';
import 'package:path_provider/path_provider.dart';
import 'dart:convert';
import 'dart:io';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(MaterialApp(
    theme: ThemeData.dark().copyWith(
      scaffoldBackgroundColor: const Color(0xFF121212),
      colorScheme: const ColorScheme.dark(primary: Colors.redAccent),
    ),
    home: const InterventionPage(),
    debugShowCheckedModeBanner: false,
  ));
}

class InterventionPage extends StatefulWidget {
  const InterventionPage({super.key});
  @override
  State<InterventionPage> createState() => _InterventionPageState();
}

class _InterventionPageState extends State<InterventionPage> {
  final FlutterTts _tts = FlutterTts();
  final AudioPlayer _audio = AudioPlayer();
  static const platform = MethodChannel('com.tomtom.intervention/notify');
  
  Map<String, String> _monitoredApps = {};
  List<Map<String, String>> _rules = [];
  List<String> _ignoreWords = [];
  List<String> _logs = [];
  List<String> _customSounds = []; // noms de fichiers stockés dans /sounds
  Map<String, String> _appSounds = {}; // packageName -> son déclenché sur toute notification
  String _orbeWatchName = 'CALABRO'; // nom de pompier recherché dans Orbe Viewer

  @override
  void initState() {
    super.initState();
    _initTTS();
    _configureAudioForCar();
    _loadData();
    
    platform.setMethodCallHandler((call) async {
      if (call.method == "onNotificationReceived") {
        final arguments = call.arguments;
        if (arguments is String) {
          _executeAction(arguments, packageName: "");
        } else if (arguments is Map) {
          final String msg = arguments['message'] ?? "";
          final String pkg = arguments['packageName'] ?? "";
          _executeAction(msg, packageName: pkg);
        }
      }
      return null;
    });
  }

  void _initTTS() async {
  await _tts.setLanguage("fr-FR");
  await _tts.setSpeechRate(0.5);
  await _tts.setVolume(1.0);
  await _tts.setAudioAttributesForNavigation();
  }

  // Force le lecteur audio à se comporter comme les instructions vocales de
  // Waze (usage "navigation guidance") : ce type d'usage est traité en
  // priorité par Android/Android Auto, qui baisse ou coupe la musique en
  // cours au lieu de jouer l'alerte au même niveau qu'elle.
  Future<void> _configureAudioForCar() async {
    await _audio.setAudioContext(AudioContext(
      android: const AudioContextAndroid(
        isSpeakerphoneOn: false,
        stayAwake: true,
        contentType: AndroidContentType.speech,
        usageType: AndroidUsageType.assistanceNavigationGuidance,
        audioFocus: AndroidAudioFocus.gainTransientMayDuck,
      ),
    ));
  }

  Future<void> _loadData() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      _monitoredApps = Map<String, String>.from(json.decode(prefs.getString('apps') ?? '{}'));
      _rules = (json.decode(prefs.getString('rules') ?? '[]') as List)
          .map((e) => Map<String, String>.from(e))
          .toList();
      _ignoreWords = prefs.getStringList('ignore_words') ?? [];
      _logs = prefs.getStringList('logs') ?? [];
      _customSounds = prefs.getStringList('custom_sounds') ?? [];
      _appSounds = Map<String, String>.from(json.decode(prefs.getString('app_sounds') ?? '{}'));
      _orbeWatchName = prefs.getString('orbe_watch_name') ?? 'CALABRO';
    });
  }

  Future<void> _saveData() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('apps', json.encode(_monitoredApps));
    await prefs.setString('rules', json.encode(_rules));
    await prefs.setStringList('ignore_words', _ignoreWords);
    await prefs.setString('selected_packages', _monitoredApps.keys.join(','));
    await prefs.setStringList('logs', _logs);
    await prefs.setStringList('custom_sounds', _customSounds);
    await prefs.setString('app_sounds', json.encode(_appSounds));
    await prefs.setString('orbe_watch_name', _orbeWatchName);
  }

  // Dossier stable où sont copiés les sons personnalisés choisis par l'utilisateur
  Future<Directory> _soundsDir() async {
    final dir = await getApplicationDocumentsDirectory();
    final soundsDir = Directory('${dir.path}/sounds');
    if (!await soundsDir.exists()) {
      await soundsDir.create(recursive: true);
    }
    return soundsDir;
  }

  // Ouvre le sélecteur de documents, copie le fichier choisi dans un dossier
  // stable de l'app (pour ne pas dépendre d'une URI temporaire), et l'ajoute
  // à la liste des sons disponibles. Retourne le nom de fichier ajouté, ou
  // null si l'utilisateur a annulé.
  Future<String?> _pickCustomSound() async {
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.audio,
        withData: true,
      );
      if (result == null || result.files.isEmpty) return null;

      final picked = result.files.single;
      final bytes = picked.bytes ??
          (picked.path != null ? await File(picked.path!).readAsBytes() : null);
      if (bytes == null) return null;

      String baseName = picked.name;
      final dir = await _soundsDir();
      String finalName = baseName;
      int counter = 1;
      while (await File('${dir.path}/$finalName').exists()) {
        final dot = baseName.lastIndexOf('.');
        final stem = dot > 0 ? baseName.substring(0, dot) : baseName;
        final ext = dot > 0 ? baseName.substring(dot) : '';
        finalName = '$stem($counter)$ext';
        counter++;
      }

      final file = File('${dir.path}/$finalName');
      await file.writeAsBytes(bytes);

      setState(() {
        if (!_customSounds.contains(finalName)) {
          _customSounds.add(finalName);
        }
      });
      _saveData();
      _addLog("Son personnalisé ajouté : $finalName");
      return finalName;
    } catch (e) {
      _addLog("Erreur lors de l'import du son : $e");
      return null;
    }
  }

  void _deleteCustomSound(String name) async {
    try {
      final dir = await _soundsDir();
      final file = File('${dir.path}/$name');
      if (await file.exists()) await file.delete();
    } catch (_) {}
    setState(() => _customSounds.remove(name));
    _saveData();
  }

  void _addLog(String entry) {
    final timestamp = DateTime.now().toIso8601String().replaceFirst('T', ' ').split('.').first;
    setState(() {
      _logs.insert(0, '[$timestamp] $entry');
      if (_logs.length > 200) {
        _logs = _logs.sublist(0, 200);
      }
    });
    _saveData();
  }

  void _clearLogs() {
    setState(() => _logs.clear());
    _saveData();
  }

  // --- LOGIQUE DE TRAITEMENT DES ALERTES ---
  void _executeAction(String message, {required String packageName}) async {
    final prefs = await SharedPreferences.getInstance();
    final List<String> currentIgnoreList = prefs.getStringList('ignore_words') ?? [];
    
    String msgLower = message.toLowerCase().trim();

    // 1. VÉRIFICATION PRIORITAIRE DES MOTS À IGNORER
    for (var word in currentIgnoreList) {
      if (word.isNotEmpty && msgLower.contains(word.toLowerCase().trim())) {
        debugPrint("ALERTE BLOQUÉE : Contient le mot interdit '$word'");
        _addLog("Alerte bloquée car contient le mot ignoré '$word' : $message");
        return;
      }
    }

    // 2. ANALYSE DES RÈGLES SPÉCIFIQUES
    bool ruleFound = false;
    for (var rule in _rules) {
      if (msgLower.contains(rule['if']!.toLowerCase().trim())) {
        ruleFound = true;
        String texteADire = rule['say']!.replaceAll("{notif}", message);
        _addLog("Règle appliquée : si '${rule['if']}' => '${rule['say']}' avec son '${rule['sound']}'");
        await _runAlerte(rule['sound']!, texteADire, packageName: packageName);
        break;
      }
    }

    // 3. LECTURE PAR DÉFAUT (Si aucune règle ne matche)
    if (!ruleFound) {
      if (packageName.isNotEmpty && _monitoredApps.containsKey(packageName)) {
        // App surveillée (ex: Sauv'Life, Staying Alive) : on déclenche
        // l'alerte complète (son + voix + ouverture) pour TOUTE notification,
        // pas seulement celles qui matchent une règle texte.
        final String sound = _appSounds[packageName] ?? "gare.ogg";
        _addLog("Notification de ${_monitoredApps[packageName]} : alerte automatique ($sound) : $message");
        await _runAlerte(sound, message, packageName: packageName);
      } else {
        _addLog("Lecture par défaut : $message");
        await platform.invokeMethod('forceMaxVolume');
        await _tts.speak(message, focus: true);
      }
    }
  }

  Future<void> _runAlerte(String sound, String text, {required String packageName}) async {
    await platform.invokeMethod('forceMaxVolume');
    await _configureAudioForCar();
    
    if (sound != "Aucun") {
      await _audio.setVolume(1.0);
      final source = (sound == "gare.ogg" || sound == "bruissement.ogg")
          ? AssetSource('audio/$sound')
          : DeviceFileSource('${(await _soundsDir()).path}/$sound');
      await _audio.play(source);

      // On évite _audio.onPlayerComplete (bug connu du package audioplayers :
      // il déclenche en interne un .timeout() mal typé qui plante et coupait
      // l'attente court-circuit, provoquant le chevauchement des sons).
      // On attend simplement la durée réelle du fichier à la place.
      Duration? duration;
      try {
        duration = await _audio.getDuration();
      } catch (e) {
        _addLog("Impossible de lire la durée du son '$sound' : $e");
      }
      if (duration != null && duration > Duration.zero) {
        await Future.delayed(duration);
      } else {
        // Sécurité si la durée n'a pas pu être déterminée.
        await Future.delayed(const Duration(seconds: 3));
      }
    }
    
    if (text.isNotEmpty) {
      await Future.delayed(const Duration(milliseconds: 500));
      await _tts.speak(text, focus: true);
      await Future.delayed(const Duration(seconds: 2));
    }

    if (packageName.isNotEmpty && _monitoredApps.containsKey(packageName)) {
      await _launchTargetApp(packageName);
    }
  }

  // Correction de la méthode de lancement (suppression du paramètre non défini)
  Future<void> _launchTargetApp(String packageName) async {
    try {
      final String appName = _monitoredApps[packageName] ?? "l'application";
      _addLog("Tentative d'ouverture automatique de : $appName ($packageName)");
      
      await LaunchApp.openApp(
        androidPackageName: packageName,
      );
    } catch (e) {
      _addLog("Erreur lors du lancement de l'application : $e");
    }
  }

  // --- INTERFACE ---
  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 3,
      child: Scaffold(
        appBar: AppBar(
          title: const Text("INTERVENTION", style: TextStyle(color: Colors.redAccent, fontWeight: FontWeight.bold)),
          backgroundColor: Colors.black,
          bottom: const TabBar(
            indicatorColor: Colors.redAccent,
            tabs: [Tab(text: "RÈGLES"), Tab(text: "RÉGLAGES"), Tab(text: "JOURNAL")],
          ),
        ),
        body: TabBarView(children: [_buildRulesTab(), _buildSettingsTab(), _buildLogsTab()]),
        floatingActionButton: FloatingActionButton(
          onPressed: () => _showRuleDialog(),
          backgroundColor: Colors.redAccent,
          child: const Icon(Icons.add, color: Colors.white),
        ),
      ),
    );
  }

  Widget _buildRulesTab() {
    return ListView.builder(
      itemCount: _rules.length,
      itemBuilder: (c, i) => Card(
        color: Colors.white10,
        margin: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        child: ListTile(
          onTap: () => _showRuleDialog(index: i),
          title: Text("Si: ${_rules[i]['if']}", style: const TextStyle(fontWeight: FontWeight.bold)),
          subtitle: Text("${_rules[i]['sound']} -> ${_rules[i]['say']}"),
          trailing: IconButton(
            icon: const Icon(Icons.delete, color: Colors.red), 
            onPressed: () { setState(() => _rules.removeAt(i)); _saveData(); }
          ),
        ),
      ),
    );
  }

  void _showRuleDialog({int? index}) {
    TextEditingController ifCtrl = TextEditingController(text: index != null ? _rules[index]['if'] : "");
    TextEditingController sayCtrl = TextEditingController(text: index != null ? _rules[index]['say'] : "");
    String snd = index != null ? _rules[index]['sound']! : "gare.ogg";

    showDialog(context: context, builder: (ctx) => StatefulBuilder(builder: (context, setS) => AlertDialog(
      title: Text(index == null ? "Nouvelle règle" : "Modifier la règle"),
      content: Column(mainAxisSize: MainAxisSize.min, children: [
        TextField(controller: ifCtrl, decoration: const InputDecoration(hintText: "Texte à détecter")),
        TextField(controller: sayCtrl, decoration: const InputDecoration(hintText: "Texte à dire ({notif} autorisé)")),
        Row(children: [
          Expanded(
            child: DropdownButton<String>(
              value: snd,
              isExpanded: true,
              items: ["gare.ogg", "bruissement.ogg", "Aucun", ..._customSounds]
                  .map((s) => DropdownMenuItem(value: s, child: Text(s, overflow: TextOverflow.ellipsis)))
                  .toList(),
              onChanged: (v) {
                if (v != null) {
                  setS(() => snd = v);
                }
              },
            ),
          ),
          IconButton(
            icon: const Icon(Icons.folder_open, color: Colors.redAccent),
            tooltip: "Parcourir les documents",
            onPressed: () async {
              final picked = await _pickCustomSound();
              if (picked != null) {
                setS(() => snd = picked);
              }
            },
          ),
        ]),
      ]),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx), child: const Text("ANNULER")),
        ElevatedButton(onPressed: () {
          setState(() {
            var data = {'if': ifCtrl.text, 'say': sayCtrl.text, 'sound': snd};
            if (index == null) {
              _rules.add(data);
            } else {
              _rules[index] = data;
            }
          });
          _saveData();
          Navigator.pop(ctx);
        }, child: const Text("VALIDER"))
      ],
    )));
  }

  Widget _buildSettingsTab() {
    return SingleChildScrollView(
      padding: const EdgeInsets.only(bottom: 100),
      child: Column(children: [
        const ListTile(title: Text("APPLICATIONS SURVEILLÉES", style: TextStyle(color: Colors.redAccent, fontWeight: FontWeight.bold))),
        const Padding(
          padding: EdgeInsets.symmetric(horizontal: 16),
          child: Text(
            "Toute notification de ces apps déclenche automatiquement le son choisi, même sans règle.",
            style: TextStyle(color: Colors.white54, fontSize: 12),
          ),
        ),
        ..._monitoredApps.entries.map((e) => ListTile(
          leading: const Icon(Icons.android, color: Colors.green),
          title: Text(e.value),
          subtitle: Text("Son : ${_appSounds[e.key] ?? 'gare.ogg'}"),
          trailing: Row(mainAxisSize: MainAxisSize.min, children: [
            IconButton(
              icon: const Icon(Icons.music_note, color: Colors.redAccent),
              tooltip: "Choisir le son",
              onPressed: () => _showAppSoundPicker(e.key),
            ),
            IconButton(icon: const Icon(Icons.remove_circle), onPressed: () { setState(() => _monitoredApps.remove(e.key)); _saveData(); }),
          ]),
        )),
        ElevatedButton(onPressed: _showAppPicker, child: const Text("AJOUTER UNE APP")),
        
        const Divider(height: 40),
        const ListTile(title: Text("SAUVEGARDE / TRANSFERT", style: TextStyle(color: Colors.redAccent, fontWeight: FontWeight.bold))),
        Row(mainAxisAlignment: MainAxisAlignment.spaceEvenly, children: [
          ElevatedButton.icon(onPressed: _exportRules, icon: const Icon(Icons.copy), label: const Text("EXPORTER")),
          ElevatedButton.icon(onPressed: _importRules, icon: const Icon(Icons.paste), label: const Text("IMPORTER")),
        ]),

        const Divider(height: 40),
        const ListTile(title: Text("SONS PERSONNALISÉS", style: TextStyle(color: Colors.redAccent, fontWeight: FontWeight.bold))),
        ..._customSounds.map((s) => ListTile(
          leading: const Icon(Icons.music_note, color: Colors.redAccent),
          title: Text(s),
          trailing: IconButton(icon: const Icon(Icons.delete, color: Colors.red), onPressed: () => _deleteCustomSound(s)),
        )),
        ElevatedButton.icon(
          onPressed: _pickCustomSound,
          icon: const Icon(Icons.folder_open),
          label: const Text("PARCOURIR LES DOCUMENTS"),
        ),

        const Divider(height: 40),
        const ListTile(title: Text("MOTS À IGNORER (SILENCE TOTAL)", style: TextStyle(color: Colors.redAccent, fontWeight: FontWeight.bold))),
        ..._ignoreWords.map((w) => ListTile(
          title: Text(w), 
          trailing: IconButton(icon: const Icon(Icons.delete), onPressed: () { setState(() => _ignoreWords.remove(w)); _saveData(); })
        )),
        ElevatedButton(onPressed: _addIgnoreWord, child: const Text("AJOUTER UN MOT À IGNORER")),

        const Divider(height: 40),
        const ListTile(title: Text("SURVEILLANCE ORBE", style: TextStyle(color: Colors.redAccent, fontWeight: FontWeight.bold))),
        const Padding(
          padding: EdgeInsets.symmetric(horizontal: 16),
          child: Text(
            "Nom de pompier recherché automatiquement dans Orbe Viewer quand une alerte NexSIS arrive.",
            style: TextStyle(color: Colors.white54, fontSize: 12),
          ),
        ),
        ListTile(
          leading: const Icon(Icons.person_search, color: Colors.redAccent),
          title: Text(_orbeWatchName),
          trailing: IconButton(
            icon: const Icon(Icons.edit, color: Colors.redAccent),
            onPressed: _showOrbeWatchNameDialog,
          ),
        ),
      ]),
    );
  }

  Widget _buildLogsTab() {
    return Column(
      children: [
        ListTile(
          tileColor: Colors.black,
          title: const Text("Journal des événements", style: TextStyle(color: Colors.redAccent, fontWeight: FontWeight.bold)),
          trailing: TextButton(
            onPressed: _logs.isEmpty ? null : _clearLogs,
            child: const Text("EFFACER", style: TextStyle(color: Colors.redAccent)),
          ),
        ),
        Expanded(
          child: _logs.isEmpty
              ? const Center(child: Text("Aucun log pour le moment", style: TextStyle(color: Colors.white70)))
              : ListView.separated(
                  padding: const EdgeInsets.all(8),
                  itemCount: _logs.length,
                  separatorBuilder: (_, __) => const Divider(color: Colors.white12),
                  itemBuilder: (context, index) => ListTile(
                    title: Text(_logs[index], style: const TextStyle(fontSize: 14)),
                  ),
                ),
        ),
      ],
    );
  }

  void _exportRules() {
    Clipboard.setData(ClipboardData(text: json.encode(_rules)));
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text("Règles copiées !")));
  }

  void _importRules() {
    TextEditingController controller = TextEditingController();
    showDialog(context: context, builder: (ctx) => AlertDialog(
      title: const Text("Importer JSON"),
      content: TextField(controller: controller, maxLines: 5, decoration: const InputDecoration(hintText: "Collez ici")),
      actions: [ElevatedButton(onPressed: () {
        try {
          setState(() {
            _rules = (json.decode(controller.text) as List)
                .map((e) => Map<String, String>.from(e))
                .toList();
          });
          _saveData();
          Navigator.pop(ctx);
        } catch (e) {
          ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text("Format invalide")));
        }
      }, child: const Text("OK"))],
    ));
  }

  void _showOrbeWatchNameDialog() {
    final controller = TextEditingController(text: _orbeWatchName);
    showDialog(context: context, builder: (ctx) => AlertDialog(
      title: const Text("Nom à surveiller sur Orbe"),
      content: TextField(
        controller: controller,
        autofocus: true,
        textCapitalization: TextCapitalization.characters,
        decoration: const InputDecoration(hintText: "Ex: CALABRO"),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx), child: const Text("ANNULER")),
        ElevatedButton(onPressed: () {
          if (controller.text.trim().isNotEmpty) {
            setState(() => _orbeWatchName = controller.text.trim());
            _saveData();
          }
          Navigator.pop(ctx);
        }, child: const Text("VALIDER")),
      ],
    ));
  }

  void _addIgnoreWord() {
    String word = "";
    showDialog(context: context, builder: (ctx) => AlertDialog(
      title: const Text("Ignorer si contient :"),
      content: TextField(onChanged: (v) => word = v, decoration: const InputDecoration(hintText: "Ex: systématique")),
      actions: [ElevatedButton(onPressed: () {
        if (word.isNotEmpty) {
          setState(() => _ignoreWords.add(word));
          _saveData();
        }
        Navigator.pop(ctx);
      }, child: const Text("OK"))],
    ));
  }

  void _showAppSoundPicker(String packageName) {
    String snd = _appSounds[packageName] ?? "gare.ogg";
    showDialog(context: context, builder: (ctx) => StatefulBuilder(builder: (context, setS) => AlertDialog(
      title: Text("Son pour ${_monitoredApps[packageName]}"),
      content: Row(children: [
        Expanded(
          child: DropdownButton<String>(
            value: snd,
            isExpanded: true,
            items: ["gare.ogg", "bruissement.ogg", "Aucun", ..._customSounds]
                .map((s) => DropdownMenuItem(value: s, child: Text(s, overflow: TextOverflow.ellipsis)))
                .toList(),
            onChanged: (v) {
              if (v != null) setS(() => snd = v);
            },
          ),
        ),
        IconButton(
          icon: const Icon(Icons.folder_open, color: Colors.redAccent),
          tooltip: "Parcourir les documents",
          onPressed: () async {
            final picked = await _pickCustomSound();
            if (picked != null) setS(() => snd = picked);
          },
        ),
      ]),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx), child: const Text("ANNULER")),
        ElevatedButton(onPressed: () {
          setState(() => _appSounds[packageName] = snd);
          _saveData();
          Navigator.pop(ctx);
        }, child: const Text("VALIDER")),
      ],
    )));
  }

  void _showAppPicker() async {
    final List<dynamic> rawApps = await InstalledApps.getInstalledApps();
    List<AppInfo> apps = rawApps.map((e) => e as AppInfo).toList();
    
    // CORRECTION APPLIQUÉE : Plus aucun opérateur ?? superflu ici
    apps.sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
    
    if (!mounted) return;

    final double targetHeight = MediaQuery.of(context).size.height * 0.8;

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (ctx) => SizedBox(
        height: targetHeight,
        child: ListView.builder(
          itemCount: apps.length,
          itemBuilder: (c, i) {
            // CORRECTION APPLIQUÉE : Lecture directe et propre des propriétés non-nulles
            final String appName = apps[i].name;
            final String packageName = apps[i].packageName;
            
            if (packageName.isEmpty) {
              return const SizedBox.shrink();
            }
            return ListTile(
              title: Text(appName),
              onTap: () {
                setState(() {
                  _monitoredApps[packageName] = appName;
                });
                _saveData();
                Navigator.pop(ctx);
              },
            );
          },
        ),
      ),
    );
  }
}
