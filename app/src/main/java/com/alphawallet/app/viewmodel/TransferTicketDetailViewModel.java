package com.alphawallet.app.viewmodel;

import android.app.Activity;
import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.alphawallet.app.C;
import com.alphawallet.app.entity.AnalyticsProperties;
import com.alphawallet.app.entity.ContractType;
import com.alphawallet.app.entity.CryptoFunctions;
import com.alphawallet.app.entity.GasSettings;
import com.alphawallet.app.entity.Operation;
import com.alphawallet.app.entity.SignAuthenticationCallback;
import com.alphawallet.app.entity.TransactionData;
import com.alphawallet.app.entity.Wallet;
import com.alphawallet.app.entity.cryptokeys.SignatureFromKey;
import com.alphawallet.app.entity.opensea.Asset;
import com.alphawallet.app.entity.tokens.Token;
import com.alphawallet.app.interact.CreateTransactionInteract;
import com.alphawallet.app.interact.ENSInteract;
import com.alphawallet.app.interact.FetchTransactionsInteract;
import com.alphawallet.app.interact.GenericWalletInteract;
import com.alphawallet.app.repository.EthereumNetworkRepository;
import com.alphawallet.app.repository.TokenRepository;
import com.alphawallet.app.router.AssetDisplayRouter;
import com.alphawallet.app.router.TransferTicketDetailRouter;
import com.alphawallet.app.service.AnalyticsServiceType;
import com.alphawallet.app.service.AssetDefinitionService;
import com.alphawallet.app.service.GasService;
import com.alphawallet.app.service.KeyService;
import com.alphawallet.app.service.TokensService;
import com.alphawallet.app.util.Utils;
import com.alphawallet.app.web3.entity.Web3Transaction;
import com.alphawallet.token.entity.SalesOrderMalformed;
import com.alphawallet.token.entity.SignableBytes;
import com.alphawallet.token.tools.ParseMagicLink;

import java.math.BigInteger;
import java.util.List;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

/**
 * Created by James on 21/02/2018.
 */
public class TransferTicketDetailViewModel extends BaseViewModel {
    private final MutableLiveData<Wallet> defaultWallet = new MutableLiveData<>();
    private final MutableLiveData<String> newTransaction = new MutableLiveData<>();
    private final MutableLiveData<String> universalLinkReady = new MutableLiveData<>();
    private final MutableLiveData<String> userTransaction = new MutableLiveData<>();
    private final MutableLiveData<TransactionData> transactionFinalised = new MutableLiveData<>();
    private final MutableLiveData<Throwable> transactionError = new MutableLiveData<>();

    private final GenericWalletInteract genericWalletInteract;
    private final KeyService keyService;
    private final CreateTransactionInteract createTransactionInteract;
    private final TransferTicketDetailRouter transferTicketDetailRouter;
    private final FetchTransactionsInteract fetchTransactionsInteract;
    private final AssetDisplayRouter assetDisplayRouter;
    private final AssetDefinitionService assetDefinitionService;
    private final GasService gasService;
    private final AnalyticsServiceType analyticsService;
    private final ENSInteract ensInteract;
    private final TokensService tokensService;

    private ParseMagicLink parser;
    private Token token;

    private byte[] linkMessage;

    TransferTicketDetailViewModel(GenericWalletInteract genericWalletInteract,
                                  KeyService keyService,
                                  CreateTransactionInteract createTransactionInteract,
                                  TransferTicketDetailRouter transferTicketDetailRouter,
                                  FetchTransactionsInteract fetchTransactionsInteract,
                                  AssetDisplayRouter assetDisplayRouter,
                                  AssetDefinitionService assetDefinitionService,
                                  GasService gasService,
                                  ENSInteract ensInteract,
                                  AnalyticsServiceType analyticsService,
                                  TokensService tokensService) {
        this.genericWalletInteract = genericWalletInteract;
        this.keyService = keyService;
        this.createTransactionInteract = createTransactionInteract;
        this.transferTicketDetailRouter = transferTicketDetailRouter;
        this.fetchTransactionsInteract = fetchTransactionsInteract;
        this.assetDisplayRouter = assetDisplayRouter;
        this.assetDefinitionService = assetDefinitionService;
        this.gasService = gasService;
        this.analyticsService = analyticsService;
        this.ensInteract = ensInteract;
        this.tokensService = tokensService;
    }


    public MutableLiveData<TransactionData> transactionFinalised()
    {
        return transactionFinalised;
    }
    public MutableLiveData<Throwable> transactionError() { return transactionError; }

    public LiveData<Wallet> defaultWallet()
    {
        return defaultWallet;
    }
    public LiveData<String> newTransaction() { return newTransaction; }
    public LiveData<String> universalLinkReady() { return universalLinkReady; }
    public LiveData<String> userTransaction() { return userTransaction; }
    private void initParser()
    {
        if (parser == null)
        {
            parser = new ParseMagicLink(new CryptoFunctions(), EthereumNetworkRepository.extraChains());
        }
    }

    public void prepare(Token token)
    {
        this.token = token;
        disposable = genericWalletInteract
                .find()
                .subscribe(this::onDefaultWallet, this::onError);

        gasService.startGasListener(token.tokenInfo.chainId);
    }

    private void onDefaultWallet(Wallet wallet) {
        defaultWallet.setValue(wallet);
    }

    public void setWallet(Wallet wallet)
    {
        defaultWallet.setValue(wallet);
    }

    private void onCreateTransaction(String transaction)
    {
        userTransaction.postValue(transaction);
    }

    public void generateUniversalLink(List<BigInteger> ticketSendIndexList, String contractAddress, long expiry)
    {
        initParser();
        if (ticketSendIndexList == null || ticketSendIndexList.size() == 0)
            return; //TODO: Display error message

        int[] indexList = Utils.bigIntegerListToIntList(ticketSendIndexList);

        //NB tradeBytes is the exact bytes the ERC875 contract builds to check the valid order.
        //This is what we must sign.
        SignableBytes tradeBytes = new SignableBytes(parser.getTradeBytes(indexList, contractAddress, BigInteger.ZERO, expiry));
        try
        {
            linkMessage = ParseMagicLink.generateLeadingLinkBytes(indexList, contractAddress, BigInteger.ZERO, expiry);
        }
        catch (SalesOrderMalformed e)
        {
            //TODO: Display appropriate error to user
        }

        //sign this link
        disposable = createTransactionInteract
                .sign(defaultWallet().getValue(), tradeBytes, token.tokenInfo.chainId)
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::gotSignature, this::onError);
    }

    public void generateSpawnLink(List<BigInteger> tokenIds, String contractAddress, long expiry)
    {
        initParser();
        SignableBytes tradeBytes = new SignableBytes(parser.getSpawnableBytes(tokenIds, contractAddress, BigInteger.ZERO, expiry));
        try
        {
            linkMessage = ParseMagicLink.generateSpawnableLeadingLinkBytes(tokenIds, contractAddress, BigInteger.ZERO, expiry);
        }
        catch (SalesOrderMalformed e)
        {
            //TODO: Display appropriate error to user
        }

        //sign this link
        disposable = createTransactionInteract
                .sign(defaultWallet().getValue(), tradeBytes, token.tokenInfo.chainId)
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::gotSignature, this::onError);
    }

    private void gotSignature(SignatureFromKey signature)
    {
        String universalLink = parser.completeUniversalLink(token.tokenInfo.chainId, linkMessage, signature.signature);
        //Now open the share icon
        universalLinkReady.postValue(universalLink);
    }

    public void createTicketTransfer(String to, Token token, List<BigInteger> transferList)
    {
        if (!token.contractTypeValid())
        {
            //need to determine the spec
            disposable = fetchTransactionsInteract.queryInterfaceSpec(token.tokenInfo)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(spec -> onInterfaceSpec(spec, to, token, transferList), this::onError);
        }
        else
        {
            final byte[] data = TokenRepository.createTicketTransferData(to, transferList, token);
            GasSettings settings = gasService.getGasSettings(data, true, token.tokenInfo.chainId);
            disposable = createTransactionInteract
                    .create(defaultWallet.getValue(), token.getAddress(), BigInteger.valueOf(0), settings.gasPrice, settings.gasLimit, data, token.tokenInfo.chainId)
                    .subscribe(this::onCreateTransaction, this::onError);
        }
    }

    private void onInterfaceSpec(ContractType spec, String to, Token token, List<BigInteger> transferList)
    {
        token.setInterfaceSpec(spec);
        TokensService.setInterfaceSpec(token.tokenInfo.chainId, token.getAddress(), spec);
        createTicketTransfer(to, token, transferList);
    }

    public AssetDefinitionService getAssetDefinitionService()
    {
        return assetDefinitionService;
    }

    public void openConfirm(Context ctx, String to, Token token, String hexTokenId, String ensDetails)
    {
        //first find the asset within the token
        Asset asset = null;
        BigInteger tokenId = new BigInteger(hexTokenId, 16);

        for (Asset a : token.getTokenAssets().values())
    {
            BigInteger assetTokenId = new BigInteger(a.getTokenId());
            if (assetTokenId.equals(tokenId))
            {
                asset = a;
                break;
            }
        }
        if (asset != null)
        {

            // confirmationRouter.openERC721Transfer(ctx, to, hexTokenId, token.getAddress(), token.getFullName(), asset.getName(), ensDetails, token);
        }
    }

    public void stopGasSettingsFetch()
    {
        gasService.stopGasListener();
    }

    public void getAuthorisation(Activity activity, SignAuthenticationCallback callback)
    {
        if (defaultWallet.getValue() != null)
        {
            keyService.getAuthenticationForSignature(defaultWallet.getValue(), activity, callback);
        }
    }

    public void resetSignDialog()
    {
        keyService.resetSigningDialog();
    }

    public void completeAuthentication(Operation signData)
    {
        keyService.completeAuthentication(signData);
    }

    public void getAuthentication(Activity activity, Wallet wallet, SignAuthenticationCallback callback)
    {
        keyService.getAuthenticationForSignature(wallet, activity, callback);
    }

    public void failedAuthentication(Operation signData)
    {
        keyService.completeAuthentication(signData);
    }

    public void sendTransaction(Web3Transaction finalTx, Wallet wallet, int chainId)
    {
        disposable = createTransactionInteract
                .createWithSig(wallet, finalTx, chainId)
                .subscribe(transactionFinalised::postValue,
                        transactionError::postValue);
    }

    public void createERC721Transfer(String to, String contractAddress, String tokenId, BigInteger gasPrice, BigInteger gasLimit, int chainId)
    {
        final byte[] data = getERC721TransferBytes(to, contractAddress, tokenId, chainId);
        disposable = createTransactionInteract
                .create(defaultWallet.getValue(), contractAddress, BigInteger.valueOf(0), gasPrice, gasLimit, data, chainId)
                .subscribe(this::onCreateTransaction, this::onError);
    }

    public byte[] getERC721TransferBytes(String to, String contractAddress, String tokenId, int chainId) {
        Token token = tokensService.getToken(chainId, contractAddress);
        List<BigInteger> tokenIds = token.stringHexToBigIntegerList(tokenId);
        return TokenRepository.createERC721TransferFunction(to, token, tokenIds);
    }

    public void actionSheetConfirm(String mode)
    {
        AnalyticsProperties analyticsProperties = new AnalyticsProperties();
        analyticsProperties.setData(mode);

        analyticsService.track(C.AN_CALL_ACTIONSHEET, analyticsProperties);
    }


    public TokensService getTokenService() {
        return tokensService;
    }
}
