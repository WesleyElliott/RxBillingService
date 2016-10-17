package com.miguelbcr.io.rx_billing_service;

import android.app.Activity;
import android.app.Application;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.v4.app.Fragment;
import android.util.Log;
import com.android.vending.billing.IInAppBillingService;
import com.google.common.base.Throwables;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.miguelbcr.io.rx_billing_service.entities.BillingResponseCodes;
import com.miguelbcr.io.rx_billing_service.entities.Ignore;
import com.miguelbcr.io.rx_billing_service.entities.ProductType;
import com.miguelbcr.io.rx_billing_service.entities.Purchase;
import com.miguelbcr.io.rx_billing_service.entities.SkuDetails;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.ObservableSource;
import io.reactivex.ObservableTransformer;
import io.reactivex.Single;
import io.reactivex.SingleEmitter;
import io.reactivex.SingleOnSubscribe;
import io.reactivex.SingleSource;
import io.reactivex.functions.Action;
import io.reactivex.functions.Function;
import io.reactivex.subjects.PublishSubject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import rx_activity_result2.Result;
import rx_activity_result2.RxActivityResult;

class RxBillingServiceImpl {
  private final int VERSION = 5;
  private final RxBillingService rxBillingService;
  private final TargetUi targetUi;
  private Context context;
  private final PublishSubject<RxBillingService> serviceConnectedSubject = PublishSubject.create();
  private final PublishSubject<Purchase> purchaseSubject = PublishSubject.create();
  private IInAppBillingService appBillingService;
  private ServiceConnection serviceConnection = new ServiceConnection() {
    @Override public void onServiceDisconnected(ComponentName name) {
      Log.e("XXXXXXXXXXXXXXX", "onServiceDisconnected()");
      appBillingService = null;
      serviceConnectedSubject.onError(new Throwable("IInAppBillingService disconnected"));
    }

    @Override public void onServiceConnected(ComponentName name, final IBinder service) {
      //new Handler().postDelayed(new Runnable() {
      //  @Override public void run() {
          Log.e("XXXXXXXXXXXXXXX", "IInAppBillingService connected");
          appBillingService = IInAppBillingService.Stub.asInterface(service);
          serviceConnectedSubject.onNext(rxBillingService);
          //serviceConnectedSubject.onComplete();
        }
      //}, 3000);
    //}
  };


  RxBillingServiceImpl(RxBillingService rxBillingService, Object targetUiObject) {
    this.rxBillingService = rxBillingService;
    this.targetUi = new TargetUi(targetUiObject);
    this.context = targetUi.getContext();
    bindService();
  }

  Observable<RxBillingService> getServiceConnected() {
    return serviceConnectedSubject.take(1);
  }

  void bindService() {
    Log.e("XXXXXXXXXXXXXXX", "bindService() " + serviceConnection);
    Intent serviceIntent = new Intent("com.android.vending.billing.InAppBillingService.BIND");
    serviceIntent.setPackage("com.android.vending");
    context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
  }

  void unbindService() {
    Log.e("XXXXXXXXXXXXXXX", "unbindService()");
    if (appBillingService != null) {
      context.unbindService(serviceConnection);
      appBillingService = null;
      context = null;
      targetUi.setUi(null);
    }
  }

  Observable<Boolean> isBillingSupported(final ProductType productType) {
    return Observable.create(new ObservableOnSubscribe<Boolean>() {
      @Override public void subscribe(ObservableEmitter<Boolean> emitter) throws Exception {
        emitter.onNext(appBillingService.isBillingSupported(VERSION, context.getPackageName(),
            productType.getName()) == BillingResponseCodes.OK);
        emitter.onComplete();
      }
    }).doOnComplete(new Action() {
      @Override public void run() throws Exception {
        String className =
            targetUi.fragment() == null ? targetUi.activity().getClass().getSimpleName()
                : targetUi.fragment().getClass().getSimpleName();
        Log.e("XXXXXXXXXXXXXXX", className + " isBillingSupported doOnComplete()");
        unbindService();
      }
    });
  }

  public Single<List<SkuDetails>> getSkuDetails(final ProductType productType,
      List<String> productsId) {
    final Bundle querySkus = new Bundle();
    querySkus.putStringArrayList("ITEM_ID_LIST", (ArrayList<String>) productsId);

    return Single.create(new SingleOnSubscribe<Bundle>() {
      @Override public void subscribe(SingleEmitter<Bundle> emitter) {
        try {
          emitter.onSuccess(appBillingService.getSkuDetails(VERSION, context.getPackageName(),
              productType.getName(), querySkus));
        } catch (RemoteException e) {
          e.printStackTrace();
          emitter.onError(e);
        }
      }
    }).flatMap(new Function<Bundle, SingleSource<? extends List<SkuDetails>>>() {
      @Override public SingleSource<? extends List<SkuDetails>> apply(Bundle skuDetailsBundle)
          throws Exception {
        int response = skuDetailsBundle.getInt(SkuDetails.RESPONSE_CODE);

        if (response == BillingResponseCodes.OK) {
          List<SkuDetails> skuDetails = new ArrayList<>();
          List<String> skuDetailsStrings =
              skuDetailsBundle.getStringArrayList(SkuDetails.DETAILS_LIST);

          for (String skuDetailString : skuDetailsStrings) {
            Gson gson =
                new GsonBuilder().registerTypeAdapterFactory(GsonAdapterFactory.create()).create();
            try {
              skuDetails.add(SkuDetails.typeAdapter(gson).fromJson(skuDetailString));
            } catch (IOException e) {
              e.printStackTrace();
              return Single.error(new Throwable("Error parsing item in getSkuDetails()"));
            }
          }

          return Single.just(skuDetails);
        } else {
          return Single.error(new Throwable("getSkuDetails() error code: " + response));
        }
      }
    });
  }

  public Single<Purchase> purchase(final ProductType productType, final String productId,
      final String developerPayload) {
    return Single.create(new SingleOnSubscribe<Bundle>() {
      @Override public void subscribe(SingleEmitter<Bundle> emitter) {
        try {
          emitter.onSuccess(
              appBillingService.getBuyIntent(VERSION, context.getPackageName(), productId,
                  productType.getName(), developerPayload));
        } catch (RemoteException e) {
          e.printStackTrace();
          emitter.onError(e);
        }
      }
    }).flatMap(new Function<Bundle, SingleSource<Purchase>>() {
      @Override public SingleSource<Purchase> apply(Bundle buyBundle) throws Exception {
        int response = buyBundle.getInt(Purchase.RESPONSE_CODE);

        if (response == BillingResponseCodes.OK) {
          PendingIntent pendingIntent = buyBundle.getParcelable(Purchase.BUY_INTENT);

          if (targetUi.fragment() == null) {
            RxActivityResult.on(targetUi.activity())
                .startIntentSender(pendingIntent.getIntentSender(), new Intent(), 0, 0, 0)
                .flatMap(new Function<Result<Activity>, ObservableSource<Ignore>>() {
                  @Override public ObservableSource<Ignore> apply(Result<Activity> result)
                      throws Exception {
                    return processActivityResult(result);
                  }
                })
                .subscribe();
          } else {
            RxActivityResult.on(targetUi.fragment())
                .startIntentSender(pendingIntent.getIntentSender(), new Intent(), 0, 0, 0)
                .flatMap(new Function<Result<Fragment>, ObservableSource<?>>() {
                  @Override public ObservableSource<?> apply(Result<Fragment> result)
                      throws Exception {
                    return processActivityResult(result);
                  }
                })
                .subscribe();
          }
        } else {
          Throwables.propagate(new Throwable("purchase() error code: " + response));
        }

        return purchaseSubject.toSingle();
      }
    });
  }

  private Observable<Ignore> processActivityResult(Result result) {
    if (result.resultCode() == Activity.RESULT_OK) {
      String purchaseString = result.data().getStringExtra(Purchase.INAPP_PURCHASE_DATA);
      String signature = result.data().getStringExtra(Purchase.INAPP_DATA_SIGNATURE);
      Purchase purchase = null;
      Gson gson =
          new GsonBuilder().registerTypeAdapterFactory(GsonAdapterFactory.create()).create();
      try {
        purchase = Purchase.typeAdapter(gson).fromJson(purchaseString);
      } catch (IOException e) {
        e.printStackTrace();
        return Observable.error(new Throwable("Error parsing item in purchase()"));
      }
      purchase.setSignature(signature);
      purchaseSubject.onNext(purchase);
    } else {
      purchaseSubject.onError(new Throwable("purchase() cancelled by user"));
    }

    return Observable.just(Ignore.Get);
  }

  /**
   * Once an in-app product is purchased, it is considered to be "owned" and cannot be purchased
   * from Google Play. You must send a consumption request for the in-app product before Google
   * Play
   * makes it available for purchase again.<br/><br/>
   *
   * Important: Managed in-app products are consumable, but subscriptions are not.
   *
   * @param token The purchaseToken is part of the data returned in the INAPP_PURCHASE_DATA String
   * by the Google Play service following a successful purchase request
   * @return true if success, false otherwise
   */
  public Single<Boolean> consumePurchase(final String token) {
    return Single.create(new SingleOnSubscribe<Integer>() {
      @Override public void subscribe(SingleEmitter<Integer> emitter) {
        try {
          emitter.onSuccess(
              appBillingService.consumePurchase(VERSION, context.getPackageName(), token));
        } catch (RemoteException e) {
          e.printStackTrace();
          emitter.onError(e);
        }
      }
    }).map(new Function<Integer, Boolean>() {
      @Override public Boolean apply(Integer responseCode) throws Exception {
        return responseCode == 0;
      }
    });
  }
}
