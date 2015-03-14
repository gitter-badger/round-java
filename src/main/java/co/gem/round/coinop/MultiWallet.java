package co.gem.round.coinop;


import co.gem.round.encoding.Base58;
import co.gem.round.encoding.Hex;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.DeterministicSeed;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MultiWallet {

  public static enum Blockchain {
    TESTNET, MAINNET
  }

  private byte[] primarySeed, backupSeed;

  private DeterministicKey primaryPrivateKey, backupPrivateKey,
          backupPublicKey, cosignerPublicKey;

  private NetworkParameters networkParameters;

  private MultiWallet(NetworkParameters networkParameters) {
    this.networkParameters = networkParameters;

    SecureRandom random1 = new SecureRandom();
    SecureRandom random2 = new SecureRandom();
    this.primarySeed = new DeterministicKeyChain(random1).getSeed().getSeedBytes();
    this.backupSeed = new DeterministicKeyChain(random2).getSeed().getSeedBytes();

    this.primaryPrivateKey = HDKeyDerivation.createMasterPrivateKey(primarySeed);
    this.backupPrivateKey = HDKeyDerivation.createMasterPrivateKey(backupSeed);
    this.backupPublicKey = this.backupPrivateKey.getPubOnly();
  }

  private MultiWallet(String primaryPrivateSeed, String backupPublicSeed, String cosignerPublicSeed) {
    byte[] decoded = new byte[0];
    try {
      decoded = Base58.decode(primaryPrivateSeed);
    } catch (AddressFormatException e) {
      e.printStackTrace();
    }
    ByteBuffer buffer = ByteBuffer.wrap(decoded);
    this.networkParameters = networkParametersFromHeaderBytes(buffer.getInt());

    this.primaryPrivateKey = DeterministicKey.deserializeB58(primaryPrivateSeed, networkParameters);
    if (backupPublicSeed != null)
      this.backupPublicKey = DeterministicKey.deserializeB58(backupPublicSeed, networkParameters);
    if (cosignerPublicSeed != null)
      this.cosignerPublicKey = DeterministicKey.deserializeB58(cosignerPublicSeed, networkParameters);
  }

  public static NetworkParameters networkParametersFromBlockchain(Blockchain blockchain) {
    switch (blockchain) {
      case MAINNET:
        return NetworkParameters.fromID(NetworkParameters.ID_MAINNET);
      case TESTNET:
        return NetworkParameters.fromID(NetworkParameters.ID_TESTNET);
    }

    return NetworkParameters.fromID(NetworkParameters.ID_TESTNET);
  }

  public static NetworkParameters networkParametersFromHeaderBytes(int headerBytes) {
    if (headerBytes == 0x043587CF || headerBytes == 0x04358394)
      return NetworkParameters.fromID(NetworkParameters.ID_TESTNET);
    if (headerBytes == 0x0488B21E || headerBytes == 0x0488ADE4)
      return NetworkParameters.fromID(NetworkParameters.ID_MAINNET);

    return NetworkParameters.fromID(NetworkParameters.ID_TESTNET);
  }

  public static MultiWallet generate(Blockchain blockchain) {
    NetworkParameters networkParameters = networkParametersFromBlockchain(blockchain);
    return new MultiWallet(networkParameters);
  }

  public static MultiWallet importSeeds(String primaryPrivateSeed, String backupPublicSeed, String cosignerPublicSeed) {
    return new MultiWallet(primaryPrivateSeed, backupPublicSeed, cosignerPublicSeed);
  }

  public Blockchain blockchain() {
    if (networkParameters.getId().equals(NetworkParameters.ID_MAINNET))
      return Blockchain.MAINNET;
    else if (networkParameters.getId().equals(NetworkParameters.ID_TESTNET))
      return Blockchain.TESTNET;

    return Blockchain.TESTNET;
  }

  public String serializedPrimaryPrivateSeed() {
    return Hex.encode(this.primarySeed);
  }

  public String serializedBackupPrivateSeed() {
    return Hex.encode(this.backupSeed);
  }

  public String serializedPrimaryPrivateKey() {
    return this.primaryPrivateKey.serializePrivB58(networkParameters);
  }

  public String serializedPrimaryPublicKey() {
    return this.primaryPrivateKey.serializePubB58(networkParameters);
  }

  public String serializedBackupPrivateKey() {
    return this.backupPrivateKey.serializePrivB58(networkParameters);
  }

  public String serializedBackupPublicKey() {
    return this.backupPublicKey.serializePubB58(networkParameters);
  }

  public String serializedCosignerPublicKey() {
    return this.cosignerPublicKey.serializePubB58(networkParameters);
  }

  public void purgeSeeds() {
    this.primarySeed = null;
    this.backupSeed = null;
  }

  public DeterministicKey childPrimaryPrivateKeyFromPath(String path) {
    return childKeyFromPath(path, this.primaryPrivateKey);
  }

  public DeterministicKey childPrimaryPublicKeyFromPath(String path) {
    return childKeyFromPath(path, this.primaryPrivateKey.getPubOnly());
  }

  public DeterministicKey childBackupPublicKeyFromPath(String path) {
    return childKeyFromPath(path, this.backupPublicKey);
  }

  public DeterministicKey childCosignerPublicKeyFromPath(String path) {
    return childKeyFromPath(path, this.cosignerPublicKey);
  }

  public static DeterministicKey childKeyFromPath(String path, DeterministicKey parentKey) {
    String[] segments = path.split("/");
    DeterministicKey currentKey = parentKey;
    for (int i = 1; i < segments.length; i++) {
      int childNumber = Integer.parseInt(segments[i]);
      currentKey = HDKeyDerivation.deriveChildKey(currentKey, childNumber);
    }
    return currentKey;
  }

  public Script redeemScriptForPath(String path) {
    DeterministicKey primaryPublicKey = this.childPrimaryPublicKeyFromPath(path);
    DeterministicKey backupPublicKey = this.childBackupPublicKeyFromPath(path);
    DeterministicKey cosignerPublicKey = this.childCosignerPublicKeyFromPath(path);

    List<ECKey> pubKeys = Arrays.asList(new ECKey[]{
        backupPublicKey, cosignerPublicKey, primaryPublicKey});

    return ScriptBuilder.createMultiSigOutputScript(2, pubKeys);
  }

  public String base58SignatureForPath(String walletPath, Sha256Hash sigHash) {
    DeterministicKey primaryPrivateKey = this.childPrimaryPrivateKeyFromPath(walletPath);
    TransactionSignature signature = new TransactionSignature(primaryPrivateKey.sign(sigHash), Transaction.SigHash.ALL, false);
    return Base58.encode(signature.encodeToBitcoin());
  }

  public NetworkParameters networkParameters() {
    return networkParameters;
  }

  public List<String> signaturesForTransaction(TransactionWrapper transaction) {
    int inputIndex = 0;
    List<String> signatures = new ArrayList<String>();
    for (InputWrapper inputWrapper : transaction.inputs()) {
      String walletPath = inputWrapper.walletPath();
      Script redeemScript = this.redeemScriptForPath(walletPath);
      Sha256Hash sigHash = transaction.transaction()
          .hashForSignature(inputIndex, redeemScript, Transaction.SigHash.ALL, false);
      String base58Signature = base58SignatureForPath(walletPath, sigHash);
      signatures.add(base58Signature);
      inputIndex++;
    }
    return signatures;
  }
}
