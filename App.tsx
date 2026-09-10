import React, {useEffect, useMemo, useState} from 'react';
import {
  ActivityIndicator,
  Alert,
  Pressable,
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {barcodeCode128, buildReceipt, buildTestReceipt, qrCode, textLine, feed} from './src/escpos';
import {
  loadSettings,
  methodLabels,
  PrinterConnection,
  resolveMethod,
  saveSettings,
} from './src/printerService';
import type {
  EffectivePrinterMethod,
  PaperSize,
  PrinterDevice,
  PrinterMethod,
  PrinterSettings,
  ReceiptData,
} from './src/types';

const methods = Object.keys(methodLabels) as PrinterMethod[];
const sampleReceipt = (): ReceiptData => ({
  storeName: 'SUPERMERCADO BOM PRECO',
  storeSubtitle: 'CNPJ 12.345.678/0001-90',
  dateTime: new Date(),
  items: [
    {name: 'ARROZ TIPO 1 5KG', quantity: 1, unitPrice: 24.9},
    {name: 'FEIJAO CARIOCA 1KG', quantity: 2, unitPrice: 8.5},
    {name: 'LEITE INTEGRAL 1L', quantity: 6, unitPrice: 4.29},
    {name: 'CAFE TORRADO 500G', quantity: 1, unitPrice: 15.9},
  ],
  qrPayload: 'https://www.nfce.fazenda.gov.br/consulta',
  barcodePayload: '7891234567895',
});

function App(): React.JSX.Element {
  const [settings, setSettings] = useState<PrinterSettings>({method: 'auto', paperSize: '58mm', devices: {}});
  const [effective, setEffective] = useState<EffectivePrinterMethod>('bluetooth');
  const [connection, setConnection] = useState(() => new PrinterConnection('bluetooth'));
  const [devices, setDevices] = useState<PrinterDevice[]>([]);
  const [selected, setSelected] = useState<PrinterDevice>();
  const [networkAddress, setNetworkAddress] = useState('192.168.0.50:9100');
  const [connected, setConnected] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState('Escolha o método e procure uma impressora.');
  const [error, setError] = useState(false);

  useEffect(() => {
    loadSettings().then(async saved => {
      setSettings(saved);
      try {
        const resolved = await resolveMethod(saved.method);
        setEffective(resolved);
        setConnection(new PrinterConnection(resolved));
        const device = saved.devices[resolved];
        setSelected(device);
        if (resolved === 'network' && device) setNetworkAddress(device.address);
      } catch {
        setEffective('bluetooth');
      }
    });
  }, []);

  const updateSettings = async (next: PrinterSettings) => {
    setSettings(next);
    await saveSettings(next);
  };

  const showError = (cause: unknown) => {
    setError(true);
    setStatus(cause instanceof Error ? cause.message : String(cause));
  };

  const chooseMethod = async (method: PrinterMethod) => {
    setBusy(true);
    try {
      await connection.disconnect().catch(() => undefined);
      const resolved = await resolveMethod(method);
      const nextConnection = new PrinterConnection(resolved);
      const next = {...settings, method};
      await updateSettings(next);
      setEffective(resolved);
      setConnection(nextConnection);
      setDevices([]);
      setSelected(next.devices[resolved]);
      setConnected(false);
      setError(false);
      setStatus(method === 'auto' ? `Detectado: ${methodLabels[resolved]}` : methodLabels[resolved]);
    } catch (cause) {
      showError(cause);
    } finally {
      setBusy(false);
    }
  };

  const refresh = async () => {
    setBusy(true);
    setError(false);
    setStatus(effective === 'network' ? 'Procurando porta 9100 na rede local…' : 'Procurando impressoras…');
    try {
      const found = await connection.listDevices();
      setDevices(found);
      setStatus(found.length ? `${found.length} impressora(s) encontrada(s).` : 'Nenhuma impressora encontrada.');
    } catch (cause) {
      showError(cause);
    } finally {
      setBusy(false);
    }
  };

  const connectTo = async (device: PrinterDevice) => {
    setBusy(true);
    setError(false);
    setStatus(`Conectando a ${device.name}…`);
    try {
      const success = await connection.connect(device);
      if (!success) throw new Error(`Não foi possível conectar a ${device.name}.`);
      setSelected(device);
      setConnected(true);
      setStatus(`Conectado a ${device.name}.`);
      await updateSettings({...settings, devices: {...settings.devices, [effective]: device}});
    } catch (cause) {
      setConnected(false);
      showError(cause);
    } finally {
      setBusy(false);
    }
  };

  const connectNetwork = () => {
    const address = networkAddress.trim();
    if (!address) return;
    const normalized = address.includes(':') ? address : `${address}:9100`;
    void connectTo({name: 'Impressora de rede', address: normalized});
  };

  const disconnect = async () => {
    setBusy(true);
    try {
      await connection.disconnect();
      setConnected(false);
      setStatus('Desconectado.');
      setError(false);
    } catch (cause) {
      showError(cause);
    } finally {
      setBusy(false);
    }
  };

  const print = async (label: string, bytes: number[]) => {
    if (!connected) {
      Alert.alert('Impressora desconectada', 'Conecte uma impressora antes de imprimir.');
      return;
    }
    setBusy(true);
    setError(false);
    try {
      if (!(await connection.printBytes(bytes))) throw new Error('A impressora recusou os dados.');
      setStatus(`${label} enviado para impressão.`);
    } catch (cause) {
      showError(cause);
    } finally {
      setBusy(false);
    }
  };

  const paperDots = settings.paperSize === '58mm' ? 300 : 420;
  const tests = useMemo(() => [
    {label: 'Teste completo', build: () => buildTestReceipt(settings.paperSize)},
    {label: 'Texto', build: () => [...textLine('Texto React Native', {align: 1, scale: 1, bold: true, maxScale: 1}), ...feed(3)]},
    {label: 'QR Code', build: () => [...qrCode('https://reactnative.dev'), ...feed(3)]},
    {label: 'Código de barras', build: () => [...barcodeCode128('7891234567895', paperDots), ...feed(3)]},
    {label: 'Cupom de compra', build: () => buildReceipt(sampleReceipt(), settings.paperSize)},
  ], [paperDots, settings.paperSize]);

  return (
    <SafeAreaView style={styles.safe}>
      <StatusBar barStyle="light-content" backgroundColor="#12233f" />
      <ScrollView contentContainerStyle={styles.page}>
        <View style={styles.hero}>
          <Text style={styles.eyebrow}>REACT NATIVE • ANDROID</Text>
          <Text style={styles.title}>POS Printer</Text>
          <Text style={styles.subtitle}>Bluetooth SPP, rede ESC/POS e APIs internas em uma só interface.</Text>
        </View>

        <Section title="Método de conexão">
          <View style={styles.chips}>
            {methods.map(method => (
              <Pressable
                key={method}
                disabled={busy}
                onPress={() => void chooseMethod(method)}
                style={[styles.chip, settings.method === method && styles.chipActive]}>
                <Text style={[styles.chipText, settings.method === method && styles.chipTextActive]}>
                  {methodLabels[method]}
                </Text>
              </Pressable>
            ))}
          </View>
          {settings.method === 'auto' && <Text style={styles.hint}>Método efetivo: {methodLabels[effective]}</Text>}
        </Section>

        <Section title="Papel">
          <View style={styles.row}>
            {(['58mm', '80mm'] as PaperSize[]).map(size => (
              <Pressable
                key={size}
                onPress={() => void updateSettings({...settings, paperSize: size})}
                style={[styles.paper, settings.paperSize === size && styles.paperActive]}>
                <Text style={styles.paperTitle}>{size}</Text>
                <Text style={styles.paperCaption}>{size === '58mm' ? '384 pontos' : '576 pontos'}</Text>
              </Pressable>
            ))}
          </View>
        </Section>

        <Section title="Impressora">
          {effective === 'network' && (
            <View style={styles.networkRow}>
              <TextInput
                value={networkAddress}
                onChangeText={setNetworkAddress}
                editable={!busy}
                autoCapitalize="none"
                keyboardType="url"
                placeholder="192.168.0.50:9100"
                placeholderTextColor="#758197"
                style={styles.input}
              />
              <Button label="Conectar IP" onPress={connectNetwork} disabled={busy} />
            </View>
          )}
          <View style={styles.actionRow}>
            <Button label={effective === 'network' ? 'Buscar na rede' : 'Atualizar lista'} onPress={() => void refresh()} disabled={busy} secondary />
            {connected && <Button label="Desconectar" onPress={() => void disconnect()} disabled={busy} danger />}
          </View>
          {busy && <ActivityIndicator color="#2bd9a8" style={styles.loader} />}
          {devices.map(device => (
            <Pressable
              key={device.address}
              disabled={busy}
              onPress={() => void connectTo(device)}
              style={[styles.device, selected?.address === device.address && connected && styles.deviceActive]}>
              <View style={styles.deviceIcon}><Text>▣</Text></View>
              <View style={styles.deviceCopy}>
                <Text style={styles.deviceName}>{device.name}</Text>
                <Text style={styles.deviceAddress}>{device.address}</Text>
              </View>
              <Text style={styles.deviceState}>{selected?.address === device.address && connected ? 'CONECTADA' : 'CONECTAR'}</Text>
            </Pressable>
          ))}
          <View style={[styles.status, error ? styles.statusError : styles.statusOk]}>
            <Text style={styles.statusText}>{status}</Text>
          </View>
        </Section>

        <Section title="Testes de impressão">
          <View style={styles.testGrid}>
            {tests.map(test => (
              <Pressable
                key={test.label}
                disabled={busy || !connected}
                onPress={() => void print(test.label, test.build())}
                style={[styles.testButton, (busy || !connected) && styles.disabled]}>
                <Text style={styles.testLabel}>{test.label}</Text>
                <Text style={styles.testArrow}>→</Text>
              </Pressable>
            ))}
          </View>
        </Section>
      </ScrollView>
    </SafeAreaView>
  );
}

function Section({title, children}: React.PropsWithChildren<{title: string}>) {
  return <View style={styles.section}><Text style={styles.sectionTitle}>{title}</Text>{children}</View>;
}

function Button({label, onPress, disabled, secondary, danger}: {label: string; onPress: () => void; disabled?: boolean; secondary?: boolean; danger?: boolean}) {
  return (
    <Pressable disabled={disabled} onPress={onPress} style={[styles.button, secondary && styles.buttonSecondary, danger && styles.buttonDanger, disabled && styles.disabled]}>
      <Text style={styles.buttonText}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  safe: {flex: 1, backgroundColor: '#091321'},
  page: {paddingBottom: 40, backgroundColor: '#f2f5f7'},
  hero: {backgroundColor: '#12233f', paddingHorizontal: 22, paddingTop: 28, paddingBottom: 34},
  eyebrow: {color: '#2bd9a8', fontWeight: '800', fontSize: 11, letterSpacing: 1.8},
  title: {color: '#fff', fontSize: 34, fontWeight: '900', marginTop: 5},
  subtitle: {color: '#bac6d8', fontSize: 14, lineHeight: 20, marginTop: 6, maxWidth: 420},
  section: {marginHorizontal: 16, marginTop: 16, padding: 16, borderRadius: 16, backgroundColor: '#fff', elevation: 2},
  sectionTitle: {fontSize: 17, fontWeight: '800', color: '#12233f', marginBottom: 13},
  chips: {flexDirection: 'row', flexWrap: 'wrap', gap: 8},
  chip: {borderWidth: 1, borderColor: '#d5dce5', paddingVertical: 9, paddingHorizontal: 11, borderRadius: 9, backgroundColor: '#f8fafb'},
  chipActive: {backgroundColor: '#146c61', borderColor: '#146c61'},
  chipText: {color: '#34445d', fontSize: 12, fontWeight: '600'},
  chipTextActive: {color: '#fff'},
  hint: {color: '#146c61', fontSize: 12, fontWeight: '700', marginTop: 12},
  row: {flexDirection: 'row', gap: 10},
  paper: {flex: 1, borderWidth: 1, borderColor: '#d9e0e8', borderRadius: 12, padding: 14},
  paperActive: {borderColor: '#146c61', backgroundColor: '#e8f7f3'},
  paperTitle: {fontSize: 17, fontWeight: '800', color: '#12233f'},
  paperCaption: {fontSize: 11, color: '#69778d', marginTop: 2},
  networkRow: {gap: 9, marginBottom: 12},
  input: {borderWidth: 1, borderColor: '#cbd4df', backgroundColor: '#f8fafb', borderRadius: 10, paddingHorizontal: 12, color: '#12233f', minHeight: 46},
  actionRow: {flexDirection: 'row', flexWrap: 'wrap', gap: 9},
  button: {backgroundColor: '#146c61', paddingHorizontal: 15, paddingVertical: 12, borderRadius: 9, alignItems: 'center'},
  buttonSecondary: {backgroundColor: '#334b6b'},
  buttonDanger: {backgroundColor: '#9d3843'},
  buttonText: {color: '#fff', fontWeight: '800', fontSize: 12},
  loader: {marginVertical: 12},
  device: {marginTop: 10, padding: 12, borderWidth: 1, borderColor: '#dfe5eb', borderRadius: 11, flexDirection: 'row', alignItems: 'center'},
  deviceActive: {borderColor: '#24a984', backgroundColor: '#ecfaf6'},
  deviceIcon: {width: 36, height: 36, borderRadius: 8, backgroundColor: '#dbe4ef', alignItems: 'center', justifyContent: 'center'},
  deviceCopy: {flex: 1, marginLeft: 10},
  deviceName: {fontWeight: '800', color: '#172b47'},
  deviceAddress: {fontSize: 12, color: '#6b788b', marginTop: 2},
  deviceState: {fontSize: 10, color: '#146c61', fontWeight: '900'},
  status: {borderRadius: 9, marginTop: 12, padding: 11},
  statusOk: {backgroundColor: '#e8f7f3'},
  statusError: {backgroundColor: '#fdecef'},
  statusText: {color: '#263750', fontSize: 12, lineHeight: 17},
  testGrid: {gap: 8},
  testButton: {backgroundColor: '#12233f', borderRadius: 10, padding: 14, flexDirection: 'row', justifyContent: 'space-between'},
  testLabel: {color: '#fff', fontWeight: '700'},
  testArrow: {color: '#2bd9a8', fontSize: 18, fontWeight: '900'},
  disabled: {opacity: 0.4},
});

export default App;
